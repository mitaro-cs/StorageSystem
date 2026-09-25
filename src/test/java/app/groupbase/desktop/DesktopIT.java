package app.groupbase.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.ApiClient;
import app.groupbase.IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Приложение хоста: окно на его компьютере входит по одноразовой ссылке от оболочки и не требует
 * 2FA, а участники через туннель — обычные HTTPS-клиенты.
 */
@TestPropertySource(
    properties = {
      "groupbase.desktop.enabled=true",
      "groupbase.http.insecure=false",
      "groupbase.auth.require-staff-totp=true"
    })
class DesktopIT extends IntegrationTest {

  @Autowired DesktopBridge bridge;

  private String enterPath() {
    URI u = URI.create(bridge.enterUrl());
    return u.getRawPath() + "?" + u.getRawQuery();
  }

  private static String location(ApiClient.Response r) {
    return r.raw().headers().firstValue("Location").orElse("");
  }

  @Test
  void hostWindowEntersWithoutPasswordAndTotp() {
    // До настройки ссылка ведёт на первичную настройку с уже подставленным кодом.
    if (setup.needed()) {
      var r = client().get(enterPath());
      assertThat(r.status()).isEqualTo(302);
      assertThat(location(r)).endsWith("/setup?code=" + setup.setupCode());
    }
    admin();

    ApiClient host = client();
    String path = enterPath();
    var r = host.get(path);
    assertThat(r.status()).isEqualTo(302);
    assertThat(location(r)).endsWith("/");
    var me = host.get("/api/me").json();
    assertThat(me.get("restriction").isNull()).isTrue();
    assertThat(me.get("hostWindow").asBoolean()).isTrue();
    assertThat(me.get("instance").get("desktop").asBoolean()).isTrue();
    assertThat(host.cookie("gb_session")).isNotNull();

    // Ссылка одноразовая.
    var again = client().get(path);
    assertThat(location(again)).endsWith("/login");

    // Та же локальная сессия, предъявленная через туннель, не действует.
    host.header("X-Forwarded-For", "203.0.113.7");
    assertThat(host.get("/api/me").status()).isEqualTo(401);
  }

  @Test
  void enterLinkIsIgnoredThroughTunnel() {
    admin();
    String path = enterPath();
    ApiClient outsider = client().header("X-Forwarded-For", "198.51.100.4");
    var r = outsider.get(path);
    assertThat(location(r)).endsWith("/login");
    assertThat(outsider.cookie("gb_session")).isNull();
  }

  @Test
  void passwordLoginOverTunnelGetsSecureCookies() throws Exception {
    admin();
    // Как браузер по HTTPS: Secure-cookie передаём вручную (клиент тестов ходит по HTTP).
    HttpClient http = HttpClient.newHttpClient();
    HttpResponse<String> health =
        http.send(tunnel("/api/health").GET().build(), HttpResponse.BodyHandlers.ofString());
    String csrf =
        health.headers().allValues("Set-Cookie").stream()
            .filter(c -> c.startsWith("__Host-gb_csrf="))
            .map(c -> c.substring("__Host-gb_csrf=".length(), c.indexOf(';')))
            .findFirst()
            .orElseThrow();
    assertThat(health.headers().firstValue("Strict-Transport-Security")).isPresent();

    HttpResponse<String> login =
        http.send(
            tunnel("/api/auth/login")
                .header("Content-Type", "application/json")
                .header("Cookie", "__Host-gb_csrf=" + csrf)
                .header("X-CSRF-Token", csrf)
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"" + ADMIN + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
    String session =
        login.headers().allValues("Set-Cookie").stream()
            .filter(c -> c.startsWith("__Host-gb_session="))
            .findFirst()
            .orElseThrow();
    assertThat(session).contains("Secure").contains("HttpOnly");
    String token = session.substring("__Host-gb_session=".length(), session.indexOf(';'));

    // Вход администратора с телефона — обычная сессия: без 2FA она ограничена.
    HttpResponse<String> me =
        http.send(
            tunnel("/api/me").header("Cookie", "__Host-gb_session=" + token).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(me.body()).contains("\"restriction\":\"totp_setup_required\"");
  }

  private HttpRequest.Builder tunnel(String path) {
    return HttpRequest.newBuilder(URI.create(url(path)))
        .header("X-Forwarded-For", "198.51.100.9")
        .header("X-Forwarded-Proto", "https");
  }

  @Test
  void localResponsesSkipHstsAndCarryServerMark() {
    var r = client().get("/api/health");
    assertThat(r.raw().headers().firstValue("Strict-Transport-Security")).isEmpty();
    assertThat(r.raw().headers().firstValue("X-Groupbase")).contains("1");
  }

  @Test
  void hostWindowSetsPasswordWithoutTheOldOne() {
    ApiClient host = client();
    admin();
    host.get(enterPath());
    try {
      var r =
          host.post("/api/me/password", Map.of("current", "", "password", "new-host-password-2"));
      assertThat(r.status()).as(r.body()).isEqualTo(200);

      // Вход с телефона (через туннель) — обычная сессия: там старый пароль обязателен.
      ApiClient phone = client().header("X-Forwarded-For", "198.51.100.20");
      var in =
          phone.post(
              "/api/auth/login", Map.of("username", ADMIN, "password", "new-host-password-2"));
      assertThat(in.status()).as(in.body()).isEqualTo(200);
      var denied =
          phone.post("/api/me/password", Map.of("current", "", "password", "x-pass-word-3"));
      assertThat(denied.status()).isEqualTo(400);
    } finally {
      // Прежний пароль — для остальных тестов этого класса.
      host.post("/api/me/password", Map.of("current", "", "password", ADMIN_PASSWORD));
    }
  }

  @Test
  void updateButtonInHostWindowWheneverANewVersionIsKnown() {
    ApiClient host = client();
    admin();
    host.get(enterPath());
    // Новой версии не знает ни оболочка, ни сервер — кнопки нет. А «Обновить» из меню или старой
    // страницы всё равно передаётся оболочке: она проверит сама и скажет, что версия последняя.
    assertThat(host.get("/api/admin/status").json().get("canUpdate").asBoolean()).isFalse();
    assertThat(host.post("/api/desktop/update", Map.of()).status()).isEqualTo(200);

    bridge.setAvailableUpdate("9.9.9");
    try {
      var s = host.get("/api/admin/status").json();
      assertThat(s.get("canUpdate").asBoolean()).isTrue();
      assertThat(s.get("update").get("version").asString()).isEqualTo("9.9.9");
      assertThat(host.post("/api/desktop/update", Map.of()).status()).isEqualTo(200);

      // С телефона (через туннель) обновить нельзя — только в окне на компьютере хоста.
      ApiClient phone = client().header("X-Forwarded-For", "198.51.100.30");
      assertThat(phone.post("/api/desktop/update", Map.of()).status()).isIn(401, 403);
    } finally {
      bridge.setAvailableUpdate(null);
    }
  }

  @Test
  void manageModeCanBeTurnedOff() {
    ApiClient host = client();
    admin();
    host.get(enterPath());
    assertThat(host.get("/api/me").json().get("user").get("manageMode").asBoolean()).isTrue();
    var r = host.patch("/api/me/preferences", Map.of("manageMode", false));
    assertThat(r.status()).isEqualTo(200);
    assertThat(host.get("/api/me").json().get("user").get("manageMode").asBoolean()).isFalse();
    host.patch("/api/me/preferences", Map.of("manageMode", true));
  }
}
