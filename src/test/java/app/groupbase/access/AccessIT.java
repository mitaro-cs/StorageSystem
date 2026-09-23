package app.groupbase.access;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.ApiClient;
import app.groupbase.IntegrationTest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * «Доступ для группы» через fxTunnel — с подставным сервисом fxTunnel (вход по коду, проверка
 * адреса) и подставным клиентом, который печатает то же, что настоящий.
 */
@DisabledOnOs(OS.WINDOWS)
class AccessIT extends IntegrationTest {

  private static final AtomicInteger POLLS = new AtomicInteger();
  private static HttpServer fake;

  @DynamicPropertySource
  static void fxtunnel(DynamicPropertyRegistry r) {
    try {
      fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      fake.createContext(
          "/api/auth/device/code",
          ex ->
              json(
                  ex,
                  "{\"session_id\":\"s1\",\"user_code\":\"ABCD-1234\","
                      + "\"auth_url\":\"https://fxtun.dev/cli-auth\",\"expires_in\":60}"));
      fake.createContext(
          "/api/auth/device/token",
          ex ->
              json(
                  ex,
                  POLLS.incrementAndGet() < 2
                      ? "{\"status\":\"pending\"}"
                      : "{\"status\":\"authorized\",\"token\":\"sk_test\"}"));
      fake.createContext(
          "/api/domains/check/",
          ex -> {
            String sub = ex.getRequestURI().getPath().replaceAll(".*/", "");
            boolean ok =
                "Bearer sk_test".equals(ex.getRequestHeaders().getFirst("Authorization"))
                    && !sub.equals("taken");
            json(
                ex,
                "{\"subdomain\":\"" + sub + "\",\"available\":" + ok + ",\"reason\":\"занят\"}");
          });
      fake.start();

      // Клиент туннеля: проверяет токен и печатает адрес, как fxtunnel http … --domain …
      Path bin = Files.createTempDirectory("fxtunnel").resolve("fxtunnel");
      Files.writeString(
          bin,
          """
          #!/bin/sh
          echo "  Connecting to fxtunnel server..."
          if [ "$FXTUNNEL_TOKEN" != "sk_test" ]; then
            echo "  Failed to connect: unauthorized" >&2
            exit 1
          fi
          echo "  \033[32mTunnel established!\033[0m"
          echo "  HTTPS: https://$4.fxtun.dev"
          exec sleep 300
          """);
      bin.toFile().setExecutable(true);
      r.add(
          "groupbase.access.fxtunnel-api", () -> "http://127.0.0.1:" + fake.getAddress().getPort());
      r.add("groupbase.access.fxtunnel-bin", bin::toString);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @AfterAll
  static void stopFake() {
    if (fake != null) {
      fake.stop(0);
    }
  }

  private static void json(HttpExchange ex, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(200, b.length);
    ex.getResponseBody().write(b);
    ex.close();
  }

  private static JsonNode waitFor(ApiClient a, Predicate<JsonNode> ok) throws InterruptedException {
    JsonNode v = null;
    for (int i = 0; i < 80; i++) {
      v = a.get("/api/admin/access").json();
      if (ok.test(v)) {
        return v;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("Не дождались: " + v);
  }

  @Test
  void loginPickAddressGoOnlineAndOff() throws InterruptedException {
    ApiClient a = admin();
    JsonNode v = a.get("/api/admin/access").json();
    assertThat(v.get("mode").asString()).isEqualTo("off");
    assertThat(v.get("fxtunnel").get("suggested").asString()).matches("[a-z0-9-]{6,32}");

    // Адрес без входа не включить.
    assertThat(a.put("/api/admin/access", Map.of("mode", "fxtunnel", "subdomain", "grp")).status())
        .isEqualTo(400);

    var login = a.post("/api/admin/access/fxtunnel/login", Map.of()).json();
    assertThat(login.get("fxtunnel").get("login").get("code").asString()).isEqualTo("ABCD-1234");
    waitFor(a, j -> j.get("fxtunnel").get("loggedIn").asBoolean());

    var taken = a.post("/api/admin/access/fxtunnel/check", Map.of("subdomain", "taken")).json();
    assertThat(taken.get("available").asBoolean()).isFalse();
    assertThat(a.post("/api/admin/access/fxtunnel/check", Map.of("subdomain", "-bad")).status())
        .isEqualTo(400);

    var on = a.put("/api/admin/access", Map.of("mode", "fxtunnel", "subdomain", "bin2509-test"));
    assertThat(on.status()).as(on.body()).isEqualTo(200);
    JsonNode online = waitFor(a, j -> j.get("state").asString().equals("online"));
    assertThat(online.get("url").asString()).isEqualTo("https://bin2509-test.fxtun.dev");
    assertThat(a.get("/api/me").json().get("instance").get("publicUrl").asString())
        .isEqualTo("https://bin2509-test.fxtun.dev");

    var off = a.put("/api/admin/access", Map.of("mode", "off")).json();
    assertThat(off.get("state").asString()).isEqualTo("off");
    assertThat(a.get("/api/me").json().get("instance").get("publicUrl").isNull()).isTrue();
  }

  @Test
  void manualAddressAndPermissions() {
    ApiClient a = admin();
    assertThat(a.put("/api/admin/access", Map.of("mode", "manual", "url", "ftp://x")).status())
        .isEqualTo(400);
    assertThat(
            a.put("/api/admin/access", Map.of("mode", "manual", "url", "https://g.example.ru/a"))
                .status())
        .isEqualTo(400);
    var ok = a.put("/api/admin/access", Map.of("mode", "manual", "url", "https://g.example.ru/"));
    assertThat(ok.status()).as(ok.body()).isEqualTo(200);
    assertThat(ok.json().get("url").asString()).isEqualTo("https://g.example.ru");

    // Локальная сеть — только в приложении для компьютера.
    assertThat(a.put("/api/admin/access", Map.of("mode", "lan")).status()).isEqualTo(400);

    long g = newGroup("Доступ");
    ApiClient s = newUser(g, "student").api();
    assertThat(s.get("/api/admin/access").status()).isEqualTo(403);
    assertThat(s.put("/api/admin/access", Map.of("mode", "off")).status()).isEqualTo(403);
    a.put("/api/admin/access", Map.of("mode", "off"));
  }
}
