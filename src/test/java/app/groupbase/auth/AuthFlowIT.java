package app.groupbase.auth;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.ApiClient;
import app.groupbase.IntegrationTest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuthFlowIT extends IntegrationTest {

  @Test
  void setupNeedsCodeAndRunsOnce() {
    admin();
    var r = client().get("/api/setup");
    assertThat(r.json().get("needed").asBoolean()).isFalse();
    var again =
        client()
            .post(
                "/api/setup",
                Map.of(
                    "code", setup.setupCode(),
                    "username", "intruder",
                    "displayName", "X",
                    "password", "intruder-password"));
    assertThat(again.status()).isEqualTo(409);
  }

  @Test
  void wrongSetupCodeIsRejected() {
    var r =
        client()
            .post(
                "/api/setup",
                Map.of("code", "wrong", "username", "x1", "displayName", "X", "password", "p"));
    assertThat(r.status()).isIn(403, 409);
  }

  @Test
  void loginLogoutAndMe() {
    ApiClient a = admin();
    var me = a.get("/api/me");
    assertThat(me.status()).isEqualTo(200);
    assertThat(me.json().get("user").get("username").asString()).isEqualTo(ADMIN);
    assertThat(me.json().get("user").get("instanceRole").asString()).isEqualTo("admin");
    assertThat(me.json().get("permissions").toString()).contains("manage_instance");
    assertThat(a.post("/api/auth/logout", null).status()).isEqualTo(200);
    assertThat(a.get("/api/me").status()).isEqualTo(401);
  }

  @Test
  void anonymousGets401() {
    assertThat(client().get("/api/me").status()).isEqualTo(401);
    assertThat(client().get("/api/groups").status()).isEqualTo(401);
  }

  @Test
  void badCredentialsLookTheSame() {
    admin();
    var unknown =
        client().post("/api/auth/login", Map.of("username", "nobody-here", "password", "x"));
    var wrong = client().post("/api/auth/login", Map.of("username", ADMIN, "password", "nope"));
    assertThat(unknown.status()).isEqualTo(401);
    assertThat(wrong.status()).isEqualTo(401);
    assertThat(unknown.error()).isEqualTo(wrong.error()).isEqualTo("bad_credentials");
    clock.advance(Duration.ofMinutes(16));
  }

  @Test
  void csrfIsRequiredForMutations() {
    ApiClient a = admin();
    var noHeader = a.postWithoutCsrf("/api/auth/logout", null);
    assertThat(noHeader.status()).isEqualTo(403);
    assertThat(noHeader.error()).isEqualTo("csrf");
    assertThat(a.get("/api/me").status()).isEqualTo(200);
  }

  @Test
  void rateLimitAndLockout() {
    long g = newGroup("Лимиты");
    TestUser u = newUser(g, "student");
    try {
      for (int i = 0; i < 5; i++) {
        var r =
            client().post("/api/auth/login", Map.of("username", u.username(), "password", "bad"));
        assertThat(r.status()).isEqualTo(401);
      }
      var blocked =
          client().post("/api/auth/login", Map.of("username", u.username(), "password", PASSWORD));
      assertThat(blocked.status()).isEqualTo(429);
      assertThat(blocked.error()).isEqualTo("too_many_attempts");

      // Окно ограничителя прошло, но аккаунт ещё заблокирован экспоненциальной задержкой.
      clock.advance(Duration.ofMinutes(16));
      var r = client().post("/api/auth/login", Map.of("username", u.username(), "password", "bad"));
      assertThat(r.status()).isEqualTo(401);
      var locked =
          client().post("/api/auth/login", Map.of("username", u.username(), "password", PASSWORD));
      assertThat(locked.status()).isEqualTo(429);

      clock.advance(Duration.ofMinutes(20));
      var ok =
          client().post("/api/auth/login", Map.of("username", u.username(), "password", PASSWORD));
      assertThat(ok.status()).isEqualTo(200);
    } finally {
      clock.advance(Duration.ofMinutes(16));
    }
  }

  @Test
  void sessionSlidesAndExpires() {
    long g = newGroup("Сессии");
    TestUser u = newUser(g, "student");
    clock.advance(Duration.ofDays(20));
    assertThat(u.api().get("/api/me").status()).isEqualTo(200);
    clock.advance(Duration.ofDays(20));
    // Активность на 20-й день продлила сессию ещё на 30 дней.
    assertThat(u.api().get("/api/me").status()).isEqualTo(200);
    clock.advance(Duration.ofDays(31));
    assertThat(u.api().get("/api/me").status()).isEqualTo(401);
  }

  @Test
  void blockedUserLosesSessionAndCannotLogin() {
    long g = newGroup("Блок");
    TestUser u = newUser(g, "student");
    assertThat(admin().post("/api/admin/users/" + u.id() + "/block", null).status()).isEqualTo(200);
    assertThat(u.api().get("/api/me").status()).isEqualTo(401);
    var r =
        client().post("/api/auth/login", Map.of("username", u.username(), "password", PASSWORD));
    assertThat(r.status()).isEqualTo(403);
    assertThat(r.error()).isEqualTo("blocked");
    assertThat(admin().post("/api/admin/users/" + u.id() + "/unblock", null).status())
        .isEqualTo(200);
    login(u.username(), PASSWORD);
  }

  @Test
  void sessionCookieIsHttpOnlyAndStrict() {
    admin();
    ApiClient c = client();
    var r = c.post("/api/auth/login", Map.of("username", ADMIN, "password", ADMIN_PASSWORD));
    String setCookie =
        r.raw().headers().allValues("Set-Cookie").stream()
            .filter(v -> v.startsWith("gb_session="))
            .findFirst()
            .orElseThrow();
    assertThat(setCookie).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/");
  }
}
