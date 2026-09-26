package app.groupbase.hosts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.groupbase.ApiClient;
import app.groupbase.IntegrationTest;
import app.groupbase.desktop.DesktopBridge;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Перенос сайта на другой компьютер по коду: этот сервер — компьютер, где сайт работает, а «другой
 * компьютер» — клиент переноса в тесте (так же ходит по адресу сайта).
 */
class TransferIT extends IntegrationTest {

  @DynamicPropertySource
  static void desktop(DynamicPropertyRegistry r) {
    r.add("groupbase.desktop.enabled", () -> "true");
    r.add("groupbase.hosts.tick-ms", () -> "3600000");
  }

  @Autowired HostService hosts;
  @Autowired TransferService transfers;
  @Autowired DesktopBridge bridge;

  private ApiClient window() {
    admin();
    ApiClient c = client();
    URI u = URI.create(bridge.enterUrl());
    var r = c.get(u.getRawPath() + "?" + u.getRawQuery());
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    return c;
  }

  private TransferClient other() throws IOException {
    return new TransferClient("http://127.0.0.1:" + port);
  }

  @Test
  void siteMovesByCodeAndCanComeBack() throws Exception {
    ApiClient a = admin();
    ApiClient w = window();
    long g = newGroup("Перенос");
    ApiClient student = newUser(g, "student").api();

    // Код выдаёт только администратор.
    assertThat(a.get("/api/host/transfer/code").status()).isEqualTo(204);
    assertThat(student.post("/api/host/transfer/code", Map.of()).status()).isEqualTo(403);
    var created = a.post("/api/host/transfer/code", Map.of());
    assertThat(created.status()).as(created.body()).isEqualTo(200);
    String code = created.json().get("code").asString();
    assertThat(code).matches("[2-9A-Z]{4}-[2-9A-Z]{4}-[2-9A-Z]{4}");
    assertThat(created.json().get("phase").asString()).isEqualTo("waiting");

    // Чужой код — отказ, данные не отдаются.
    Path zip = Files.createTempDirectory("groupbase-transfer").resolve("site.zip");
    assertThatThrownBy(() -> other().download("AAAA-BBBB-CCCC", zip, p -> {}))
        .hasMessageContaining("Неверный код");
    assertThat(zip).doesNotExist();

    // С кодом (регистр и пробелы не важны) — копия сайта, как резервная.
    var got = other().download(code.toLowerCase().replace("-", " "), zip, p -> {});
    assertThat(got.port()).isEqualTo(props.http().port());
    Set<String> entries = new HashSet<>();
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
      for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
        entries.add(e.getName());
      }
    }
    assertThat(entries).contains("manifest.json", "groupbase.db", "secrets/app.key");
    assertThat(a.get("/api/host/transfer/code").json().get("phase").asString()).isEqualTo("sent");

    // Пока перенос не подтвердили, читать можно, а менять — нет: изменения туда уже не попадут.
    assertThat(student.get("/api/me").status()).isEqualTo(200);
    var write = student.patch("/api/me/preferences", Map.of("manageMode", false));
    assertThat(write.status()).isEqualTo(503);
    assertThat(write.json().get("error").asString()).isEqualTo("moving");

    // Подтвердили — здесь сайт останавливается (после ответа, чтобы тот его получил).
    other().confirm(code);
    for (int i = 0; i < 50 && hosts.serving(); i++) {
      Thread.sleep(100);
    }
    assertThat(hosts.serving()).isFalse();
    assertThat(student.get("/api/me").json().get("error").asString()).isEqualTo("standby");
    var moved = w.get("/api/host").json();
    assertThat(moved.get("role").asString()).isEqualTo("moved");
    assertThat(moved.get("action").asString()).isEqualTo("return");
    assertThat(HostsConfig.load(props.dataDir()).moved()).isPositive();
    // Повтор подтверждения после обрыва связи — не ошибка; старым кодом данные больше не взять.
    other().confirm(code);
    assertThatThrownBy(() -> other().download(code, zip, p -> {})).hasMessageContaining("устарел");
    // Отсюда код больше не выдать: сайт не здесь.
    assertThat(a.post("/api/host/transfer/code", Map.of()).status()).isIn(400, 503);

    // На новом компьютере сайт не заработал — возвращаем сюда (по адресу никто не отвечает).
    assertThat(
            client()
                .header("X-Forwarded-For", "198.51.100.7")
                .post("/api/host/return", Map.of())
                .status())
        .isEqualTo(403);
    var back = w.post("/api/host/return", Map.of());
    assertThat(back.status()).as(back.body()).isEqualTo(200);
    assertThat(hosts.serving()).isTrue();
    assertThat(HostsConfig.load(props.dataDir()).moved()).isZero();
    assertThat(student.patch("/api/me/preferences", Map.of("manageMode", false)).status())
        .isEqualTo(200);
  }

  @Test
  void guessingKillsTheCode() throws Exception {
    ApiClient a = admin();
    String code = a.post("/api/host/transfer/code", Map.of()).json().get("code").asString();
    Path zip = Files.createTempDirectory("groupbase-guess").resolve("site.zip");
    for (int i = 0; i < TransferService.MAX_WRONG; i++) {
      assertThatThrownBy(() -> other().download("2222-2222-2222", zip, p -> {}))
          .isInstanceOf(IOException.class);
    }
    assertThatThrownBy(() -> other().download(code, zip, p -> {})).hasMessageContaining("устарел");
    assertThat(a.get("/api/host/transfer/code").status()).isEqualTo(204);
    // Отмена кода — изменения снова принимаются.
    a.post("/api/host/transfer/code", Map.of());
    assertThat(a.delete("/api/host/transfer/code").status()).isEqualTo(200);
    assertThat(hosts.moving()).isFalse();
  }

  @Test
  void pullOnlyFromThisComputerAndNotWhileServing() {
    ApiClient w = window();
    var busy = w.post("/api/host/pull", Map.of("url", "127.0.0.1", "code", "x"));
    assertThat(busy.status()).isEqualTo(400);
    assertThat(busy.json().get("message").asString()).contains("и так работает");
    var remote =
        client()
            .header("X-Forwarded-For", "198.51.100.8")
            .post("/api/host/pull", Map.of("url", "127.0.0.1", "code", "x"));
    assertThat(remote.status()).isEqualTo(403);
    // Первый запуск: только с кодом настройки.
    var setupWrong =
        client().post("/api/setup/transfer?code=wrong", Map.of("url", "127.0.0.1", "code", "x"));
    assertThat(setupWrong.status()).isEqualTo(403);
  }

  @Test
  void addressesAreChecked() throws IOException {
    assertThat(TransferClient.normalize("groupbase.cloudpub.ru/some/path").toString())
        .isEqualTo("https://groupbase.cloudpub.ru");
    assertThat(TransferClient.normalize(" https://groupbase.cloudpub.ru/ ").toString())
        .isEqualTo("https://groupbase.cloudpub.ru");
    assertThat(TransferClient.normalize("http://192.168.1.5:17380").toString())
        .isEqualTo("http://192.168.1.5:17380");
    // Через интернет копия с ключами открытым текстом не поедет.
    assertThatThrownBy(() -> TransferClient.normalize("http://groupbase.cloudpub.ru"))
        .hasMessageContaining("https");
    assertThatThrownBy(() -> TransferClient.normalize("ftp://example.ru"))
        .isInstanceOf(IOException.class);
    assertThatThrownBy(() -> TransferClient.normalize("  ")).isInstanceOf(IOException.class);
  }
}
