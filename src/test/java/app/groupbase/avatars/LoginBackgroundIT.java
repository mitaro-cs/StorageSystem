package app.groupbase.avatars;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.ApiClient;
import app.groupbase.IntegrationTest;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoginBackgroundIT extends IntegrationTest {

  @Test
  void backgroundIsPublicAndOnlyAdminChangesIt() throws IOException {
    ApiClient anon = client();
    ApiClient a = admin();
    assertThat(a.put("/api/admin/appearance", Map.of("loginBackground", "preset:night")).status())
        .isEqualTo(200);
    assertThat(anon.get("/api/appearance").json().get("loginBackground").asString())
        .isEqualTo("preset:night");

    assertThat(a.put("/api/admin/appearance", Map.of("loginBackground", "preset:nope")).status())
        .isEqualTo(400);
    assertThat(
            a.put("/api/admin/appearance", Map.of("loginBackground", "image:../../secrets"))
                .status())
        .isEqualTo(400);

    long g = newGroup("Фон");
    TestUser headman = newUser(g, "headman");
    assertThat(
            headman.api().put("/api/admin/appearance", Map.of("loginBackground", "none")).status())
        .isEqualTo(403);
    assertThat(
            headman
                .api()
                .putRaw("/api/admin/appearance/background", AvatarsIT.jpegWithExif())
                .status())
        .isEqualTo(403);

    // Своя картинка: перекодирована в WebP, видна без входа, при замене старая удаляется.
    var up = a.putRaw("/api/admin/appearance/background", AvatarsIT.jpegWithExif());
    assertThat(up.status()).as(up.body()).isEqualTo(200);
    String value = up.json().get("loginBackground").asString();
    assertThat(value).startsWith("image:");
    String id = value.substring("image:".length());
    var img = anon.download("/api/appearance/background/" + id + ".webp");
    assertThat(img.statusCode()).isEqualTo(200);
    assertThat(img.headers().firstValue("Content-Type")).hasValue("image/webp");
    assertThat(new String(img.body(), 0, 4, java.nio.charset.StandardCharsets.US_ASCII))
        .isEqualTo("RIFF");

    assertThat(
            a.delete("/api/admin/appearance/background").json().get("loginBackground").asString())
        .isEqualTo(LoginBackground.DEFAULT);
    assertThat(anon.download("/api/appearance/background/" + id + ".webp").statusCode())
        .isEqualTo(404);
    assertThat(anon.get("/api/appearance/background/nothex.webp").status()).isEqualTo(404);
  }

  @Test
  void notAnImageIsRejected() {
    var r = admin().putRaw("/api/admin/appearance/background", "<svg/>".getBytes());
    assertThat(r.status()).isEqualTo(415);
  }
}
