package app.groupbase.auth;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.ApiClient;
import app.groupbase.IntegrationTest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Вход по QR-коду: новое устройство ждёт, телефон с сессией подтверждает. */
class DeviceLinksIT extends IntegrationTest {

  @Test
  void phoneApprovesAndNewDeviceGetsSession() {
    long g = newGroup("QR");
    TestUser student = newUser(g, "student");
    ApiClient laptop = client();
    var started = laptop.post("/api/auth/qr", Map.of("device", "Chrome, Windows"));
    assertThat(started.status()).isEqualTo(200);
    String code = started.json().get("code").asString();
    String poll = started.json().get("poll").asString();

    assertThat(
            laptop.post("/api/auth/qr/poll", Map.of("poll", poll)).json().get("status").asString())
        .isEqualTo("waiting");
    assertThat(laptop.get("/api/me").status()).isEqualTo(401);
    // Код не даёт ничего без подтверждения, и подтвердить может только вошедший.
    assertThat(client().get("/api/auth/qr/" + code).status()).isEqualTo(401);
    assertThat(client().post("/api/auth/qr/" + code + "/approve", null).status()).isEqualTo(401);

    var info = student.api().get("/api/auth/qr/" + code);
    assertThat(info.json().get("device").asString()).isEqualTo("Chrome, Windows");
    assertThat(student.api().post("/api/auth/qr/" + code + "/approve", null).status())
        .isEqualTo(200);
    assertThat(student.api().post("/api/auth/qr/" + code + "/approve", null).status())
        .as("второй раз не выйдет")
        .isEqualTo(410);

    var done = laptop.post("/api/auth/qr/poll", Map.of("poll", poll));
    assertThat(done.json().get("status").asString()).isEqualTo("ok");
    assertThat(laptop.get("/api/me").json().get("user").get("username").asString())
        .isEqualTo(student.username());
    assertThat(laptop.post("/api/auth/qr/poll", Map.of("poll", poll)).status())
        .as("секрет опроса одноразовый")
        .isEqualTo(410);
  }

  @Test
  void codesExpireAndGarbageIsRejected() {
    long g = newGroup("QR-срок");
    TestUser student = newUser(g, "student");
    ApiClient laptop = client();
    var started = laptop.post("/api/auth/qr", Map.of("device", "x".repeat(200))).json();
    String code = started.get("code").asString();
    assertThat(student.api().get("/api/auth/qr/" + code).json().get("device").asString())
        .hasSize(60);
    clock.advance(DeviceLinks.TTL.plus(Duration.ofSeconds(1)));
    assertThat(student.api().post("/api/auth/qr/" + code + "/approve", null).status())
        .isEqualTo(410);
    assertThat(
            laptop
                .post("/api/auth/qr/poll", Map.of("poll", started.get("poll").asString()))
                .status())
        .isEqualTo(410);
    assertThat(student.api().get("/api/auth/qr/not-a-token").status()).isEqualTo(410);
    assertThat(laptop.post("/api/auth/qr/poll", Map.of("poll", "")).status()).isEqualTo(410);
  }
}
