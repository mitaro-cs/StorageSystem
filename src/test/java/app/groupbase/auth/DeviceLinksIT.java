package app.groupbase.auth;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.ApiClient;
import app.groupbase.IntegrationTest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Вход на другом устройстве (0.7): вошедший показывает QR и 6 цифр, новое устройство их предъявляет
 * и входит; показавший видит, где вошли.
 */
class DeviceLinksIT extends IntegrationTest {

  @Test
  void qrCodeLogsInNewDeviceOnce() {
    long g = newGroup("Код-QR");
    TestUser student = newUser(g, "student");
    assertThat(client().post("/api/auth/link", null).status()).as("только вошедший").isEqualTo(401);
    var issued = student.api().post("/api/auth/link", null).json();
    String code = issued.get("code").asString();
    assertThat(issued.get("pin").asString()).matches("\\d{6}");
    assertThat(
            student
                .api()
                .post("/api/auth/link/status", Map.of("code", code))
                .json()
                .get("status")
                .asString())
        .isEqualTo("waiting");

    ApiClient laptop = client();
    assertThat(laptop.get("/api/me").status()).isEqualTo(401);
    var r = laptop.post("/api/auth/link/redeem", Map.of("code", code, "device", "Chrome, Windows"));
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    assertThat(laptop.get("/api/me").json().get("user").get("username").asString())
        .isEqualTo(student.username());
    var st = student.api().post("/api/auth/link/status", Map.of("code", code)).json();
    assertThat(st.get("status").asString()).isEqualTo("used");
    assertThat(st.get("device").asString()).isEqualTo("Chrome, Windows");
    assertThat(client().post("/api/auth/link/redeem", Map.of("code", code)).status())
        .as("одноразовый")
        .isEqualTo(410);
  }

  @Test
  void sixDigitsWorkAndExpire() {
    long g = newGroup("Код-цифры");
    TestUser student = newUser(g, "student");
    String pin = student.api().post("/api/auth/link", null).json().get("pin").asString();
    ApiClient phone = client();
    var r =
        phone.post(
            "/api/auth/link/redeem",
            Map.of("pin", pin.substring(0, 3) + " " + pin.substring(3), "device", "iPhone"));
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    assertThat(phone.get("/api/me").status()).isEqualTo(200);

    String late = student.api().post("/api/auth/link", null).json().get("pin").asString();
    clock.advance(DeviceLinks.TTL.plus(Duration.ofSeconds(1)));
    assertThat(client().post("/api/auth/link/redeem", Map.of("pin", late)).status()).isEqualTo(410);
  }

  @Test
  void guessingDigitsIsCapped() {
    clock.advance(Duration.ofMinutes(11));
    long g = newGroup("Перебор");
    TestUser student = newUser(g, "student");
    for (int i = 0; i < DeviceLinks.MISSES_PER_IP; i++) {
      client().post("/api/auth/link/redeem", Map.of("pin", "000000"));
    }
    String pin = student.api().post("/api/auth/link", null).json().get("pin").asString();
    assertThat(client().post("/api/auth/link/redeem", Map.of("pin", pin)).status())
        .as("после серии ошибок — только через паузу")
        .isEqualTo(429);
    // QR (длинный код) работает и тогда.
    String code = student.api().post("/api/auth/link", null).json().get("code").asString();
    assertThat(client().post("/api/auth/link/redeem", Map.of("code", code)).status())
        .isEqualTo(200);
    clock.advance(Duration.ofMinutes(11));
  }
}
