package app.groupbase.auth;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.ApiClient;
import app.groupbase.IntegrationTest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Для admin и moderator 2FA обязательна: без неё сессия ограничена настройкой TOTP. */
@TestPropertySource(properties = "groupbase.auth.require-staff-totp=true")
class StaffTotpIT extends IntegrationTest {

  private static byte[] decodeBase32(String s) {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    int buffer = 0;
    int bits = 0;
    for (char c : s.toCharArray()) {
      buffer = (buffer << 5) | alphabet.indexOf(c);
      bits += 5;
      if (bits >= 8) {
        out.write((buffer >> (bits - 8)) & 0xff);
        bits -= 8;
      }
    }
    return out.toByteArray();
  }

  private String code(byte[] secret) {
    return Totp.code(secret, Totp.step(clock.millis()));
  }

  @Test
  void adminMustEnableTotpThenUseIt() {
    ApiClient a = admin();
    var me = a.get("/api/me").json();
    assertThat(me.get("restriction").asString()).isEqualTo("totp_setup_required");
    assertThat(me.get("groups").size()).isZero();
    assertThat(a.get("/api/groups").status()).isEqualTo(403);

    var setupResp = a.post("/api/me/totp/setup", null);
    assertThat(setupResp.status()).isEqualTo(200);
    assertThat(setupResp.json().get("uri").asString()).startsWith("otpauth://totp/");
    byte[] secret = decodeBase32(setupResp.json().get("secret").asString());

    assertThat(a.post("/api/me/totp/enable", Map.of("code", "000000")).status()).isEqualTo(400);
    assertThat(a.post("/api/me/totp/enable", Map.of("code", code(secret))).status()).isEqualTo(200);
    assertThat(a.get("/api/groups").status()).isEqualTo(200);

    // Следующий вход требует код; повтор кода из того же шага не проходит.
    clock.advance(Duration.ofSeconds(30));
    ApiClient c = client();
    var step1 = c.post("/api/auth/login", Map.of("username", ADMIN, "password", ADMIN_PASSWORD));
    assertThat(step1.json().get("status").asString()).isEqualTo("totp");
    String ticket = step1.json().get("ticket").asString();
    assertThat(c.get("/api/me").status()).isEqualTo(401);
    assertThat(c.post("/api/auth/login/totp", Map.of("ticket", ticket, "code", "123456")).status())
        .isEqualTo(401);
    String good = code(secret);
    assertThat(c.post("/api/auth/login/totp", Map.of("ticket", ticket, "code", good)).status())
        .isEqualTo(200);
    assertThat(c.get("/api/me").json().get("restriction").isNull()).isTrue();

    ApiClient d = client();
    var again = d.post("/api/auth/login", Map.of("username", ADMIN, "password", ADMIN_PASSWORD));
    var replay =
        d.post(
            "/api/auth/login/totp",
            Map.of("ticket", again.json().get("ticket").asString(), "code", good));
    assertThat(replay.status()).isEqualTo(401);

    // Отключить 2FA администратору нельзя.
    clock.advance(Duration.ofSeconds(30));
    assertThat(c.post("/api/me/totp/disable", Map.of("code", code(secret))).status())
        .isEqualTo(403);
  }

  @Override
  protected ApiClient admin() {
    // В этом контексте после включения TOTP обычный вход admin() уже не работает, поэтому
    // базовый помощник используется только один раз — в начале теста.
    return super.admin();
  }
}
