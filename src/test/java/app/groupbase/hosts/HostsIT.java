package app.groupbase.hosts;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.ApiClient;
import app.groupbase.IntegrationTest;
import app.groupbase.desktop.DesktopBridge;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Сайт на двух компьютерах хоста: этот сервер — «ноутбук», второй компьютер («ПК») — каталог данных
 * рядом, который берёт сайт так же, как при запуске приложения. Общая папка — временная «облачная».
 */
class HostsIT extends IntegrationTest {

  static Path cloud;

  @DynamicPropertySource
  static void desktop(DynamicPropertyRegistry r) throws IOException {
    cloud = Files.createTempDirectory("groupbase-cloud");
    r.add("groupbase.desktop.enabled", () -> "true");
    r.add("groupbase.hosts.cloud-root", () -> cloud.toString());
    // Шаги — по команде теста.
    r.add("groupbase.hosts.tick-ms", () -> "3600000");
  }

  @Autowired HostService hosts;
  @Autowired DesktopBridge bridge;
  @Autowired JdbcClient db;

  private long cursor() {
    return db.sql("SELECT seq FROM sqlite_sequence WHERE name = 'changes'")
        .query(Long.class)
        .optional()
        .orElse(0L);
  }

  /** Окно приложения хоста: вход по одноразовой ссылке оболочки. */
  private ApiClient window() {
    admin();
    ApiClient c = client();
    URI u = URI.create(bridge.enterUrl());
    var r = c.get(u.getRawPath() + "?" + u.getRawQuery());
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    return c;
  }

  @Test
  void siteMovesToAnotherComputerAndBack() throws Exception {
    ApiClient w = window();
    // Файл сайта — в зеркало общей папки.
    Path file = props.filesDir().resolve("zz/zz-lecture");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "конспект лекции");

    // Посмотреть можно и с телефона (через туннель), а включить — только на этом компьютере.
    ApiClient phone = client().header("X-Forwarded-For", "198.51.100.4");
    var login =
        phone.post("/api/auth/login", Map.of("username", ADMIN, "password", ADMIN_PASSWORD));
    assertThat(login.status()).as(login.body()).isEqualTo(200);
    var before = phone.get("/api/host").json();
    assertThat(before.get("available").asBoolean()).isTrue();
    assertThat(before.get("enabled").asBoolean()).isFalse();
    assertThat(before.get("choices").get(0).get("label").asString()).isEqualTo("Тестовое облако");
    var denied = phone.post("/api/host/enable", Map.of("cloud", cloud.toString()));
    assertThat(denied.status()).isEqualTo(403);
    assertThat(client().header("X-Forwarded-For", "198.51.100.5").get("/api/host").status())
        .isEqualTo(401);
    var bad = w.post("/api/host/enable", Map.of("cloud", "/tmp"));
    assertThat(bad.status()).isEqualTo(400);

    var on = w.post("/api/host/enable", Map.of("cloud", cloud.toString()));
    assertThat(on.status()).as(on.body()).isEqualTo(200);
    assertThat(on.json().get("role").asString()).isEqualTo("host");
    assertThat(on.json().get("snapshotAt").isNull()).isFalse();
    Path site = Path.of(on.json().get("folder").asString());
    assertThat(site.getParent().getFileName().toString()).isEqualTo(SiteFolder.ROOT);
    SiteFolder folder = new SiteFolder(site);
    var laptop = folder.host().orElseThrow();
    assertThat(laptop.state()).isEqualTo(SiteFolder.RUNNING);
    assertThat(laptop.epoch()).isEqualTo(1);
    assertThat(folder.snapshotNames()).hasSize(1);
    assertThat(Files.readString(site.resolve("files/zz/zz-lecture"))).isEqualTo("конспект лекции");
    assertThat(folder.site().orElseThrow().name()).isNotBlank();

    // По адресу сайта отвечает этот компьютер — без имени, только номер.
    var who = client().get("/api/host/whoami").json();
    assertThat(who.get("computer").asString()).isEqualTo(laptop.computerId());
    assertThat(who.has("name")).isFalse();

    // Второй компьютер подключили, пока сайт работает здесь: данные не берёт, ждёт.
    Path pc = Files.createTempDirectory("groupbase-pc");
    HostsConfig pcCfg = HostsConfig.load(pc);
    pcCfg.setDir(site);
    pcCfg.setComputerName("ПК дома");
    pcCfg.save();
    assertThat(HostSwitch.prepare(pc, m -> {})).isZero();
    assertThat(pc.resolve("groupbase.db")).doesNotExist();

    // «Перенести сюда» на ПК: просьба в папке — хост сохраняет всё и уступает.
    long now = clock.millis();
    folder.writeRequest(new SiteFolder.Request(pcCfg.computerId(), "ПК дома", now));
    hosts.step(now + 5_000);
    var handed = folder.host().orElseThrow();
    assertThat(handed.state()).isEqualTo(SiteFolder.STOPPED);
    assertThat(handed.to()).isEqualTo(pcCfg.computerId());
    assertThat(handed.toName()).isEqualTo("ПК дома");
    assertThat(hosts.serving()).isFalse();

    // Здесь сайт закрыт — открыта только страница ожидания.
    var me = phone.get("/api/me");
    assertThat(me.status()).isEqualTo(503);
    assertThat(me.json().get("error").asString()).isEqualTo("standby");
    assertThat(client().get("/api/host/whoami").status()).isEqualTo(404);
    var standby = w.get("/api/host").json();
    assertThat(standby.get("role").asString()).isEqualTo("standby");
    assertThat(standby.get("plan").asString()).isEqualTo("handed");
    assertThat(standby.get("message").asString()).contains("ПК дома");

    // ПК при запуске берёт данные: база, ключи, файлы — всё как здесь.
    int port = HostSwitch.prepare(pc, m -> {});
    assertThat(port).isEqualTo(props.http().port());
    assertThat(Files.readString(pc.resolve("files/zz/zz-lecture"))).isEqualTo("конспект лекции");
    assertThat(Files.readAllBytes(pc.resolve("secrets/app.key")))
        .isEqualTo(Files.readAllBytes(props.secretsDir().resolve("app.key")));
    assertThat(users(pc.resolve("groupbase.db"))).contains(ADMIN);
    HostsConfig after = HostsConfig.load(pc);
    assertThat(after.epoch()).isEqualTo(1);
    assertThat(after.snapshot()).isEqualTo(handed.snapshot().name());
    assertThat(after.pending()).isEmpty();

    // ПК работает (поколение 2) — здесь страница ожидания предлагает попросить сайт обратно.
    folder.writeHost(
        new SiteFolder.Host(
            1,
            pcCfg.computerId(),
            "ПК дома",
            2,
            SiteFolder.RUNNING,
            now + 20_000,
            now + 20_000,
            handed.snapshot(),
            null,
            null,
            "test"));
    hosts.step(now + 25_000);
    var live = w.get("/api/host").json();
    assertThat(live.get("plan").asString()).isEqualTo("live");
    assertThat(live.get("action").asString()).isEqualTo("request");
    assertThat(live.get("other").get("name").asString()).isEqualTo("ПК дома");

    // Через туннель чужой не заберёт сайт на этот компьютер.
    assertThat(phone.post("/api/host/takeover", Map.of()).status()).isEqualTo(403);
    var ask = w.post("/api/host/takeover", Map.of());
    assertThat(ask.status()).as(ask.body()).isEqualTo(200);
    assertThat(ask.json().get("role").asString()).isEqualTo("waiting");
    var request = folder.request().orElseThrow();
    assertThat(request.computerId()).isEqualTo(laptop.computerId());

    // ПК закрыли: здесь сайт берётся сам — снимок заказан к перезапуску.
    folder.writeHost(
        new SiteFolder.Host(
            1,
            pcCfg.computerId(),
            "ПК дома",
            2,
            SiteFolder.STOPPED,
            now + 40_000,
            now + 20_000,
            handed.snapshot(),
            null,
            null,
            "test"));
    hosts.step(now + 45_000);
    // Снимок тот же, что уже здесь: перезапуск не нужен — просто снова хост, поколение 3.
    assertThat(hosts.serving()).isTrue();
    assertThat(folder.host().orElseThrow().computerId()).isEqualTo(laptop.computerId());
    assertThat(folder.host().orElseThrow().epoch()).isEqualTo(3);
    assertThat(folder.request()).isEmpty();
    assertThat(phone.get("/api/me").status()).isEqualTo(200);
    // Сайт пришёл с другого компьютера: номера журнала изменений — с запасом вперёд, чтобы
    // телефоны не приняли новые изменения за уже полученные.
    long seq = cursor();
    hosts.step(now + 50_000);
    assertThat(cursor()).isEqualTo(seq + 1000);
    assertThat(folder.snapshotNames().getLast()).startsWith("s-000003-");

    // Приложение здесь закрыли — ПК при запуске берёт свежие данные сам, своё прежнее — в сторону.
    Files.writeString(pc.resolve("files/zz/zz-old"), "удалён на хосте");
    hosts.stop();
    var closed = folder.host().orElseThrow();
    assertThat(closed.state()).isEqualTo(SiteFolder.STOPPED);
    assertThat(closed.epoch()).isEqualTo(3);
    assertThat(HostSwitch.prepare(pc, m -> {})).isEqualTo(props.http().port());
    assertThat(HostsConfig.load(pc).epoch()).isEqualTo(3);
    assertThat(pc.resolve("files/zz/zz-old")).doesNotExist();
    assertThat(Files.readString(pc.resolve("files/zz/zz-lecture"))).isEqualTo("конспект лекции");
    try (var list = Files.list(pc)) {
      Path aside =
          list.filter(p -> p.getFileName().toString().startsWith("before-switch-"))
              .findFirst()
              .orElseThrow();
      assertThat(aside.resolve("groupbase.db")).exists();
      assertThat(Files.readString(aside.resolve("files/zz/zz-old"))).isEqualTo("удалён на хосте");
    }

    // Снова запустили здесь (в тестах контекст ставят на паузу и продолжают) — опять хост.
    hosts.start();
    assertThat(hosts.serving()).isTrue();
    assertThat(folder.host().orElseThrow().state()).isEqualTo(SiteFolder.RUNNING);
  }

  @Test
  void siteSnapshotIsNotABackup() throws IOException {
    ApiClient a = admin();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream z = new ZipOutputStream(bytes)) {
      z.putNextEntry(new ZipEntry("manifest.json"));
      z.write("{\"format\":1,\"kind\":\"site-snapshot\",\"schema\":1}".getBytes());
      z.closeEntry();
    }
    var r = a.putRaw("/api/admin/backups/restore", bytes.toByteArray());
    assertThat(r.status()).isEqualTo(400);
    assertThat(r.json().get("message").asString()).contains("нескольких компьютерах");
  }

  static List<String> users(Path db) throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + db + "?mode=ro");
        ResultSet rs = c.createStatement().executeQuery("SELECT username FROM users")) {
      List<String> out = new java.util.ArrayList<>();
      while (rs.next()) {
        out.add(rs.getString(1));
      }
      return out;
    }
  }
}
