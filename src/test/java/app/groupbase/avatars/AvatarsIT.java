package app.groupbase.avatars;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.ApiClient;
import app.groupbase.IntegrationTest;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class AvatarsIT extends IntegrationTest {

  private static final String SECRET = "GPS-55.7558-37.6173-iPhone15Pro";

  /** JPEG 1200×800 с APP1-сегментом Exif, в котором «координаты» и модель телефона. */
  static byte[] jpegWithExif() throws IOException {
    BufferedImage img = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setColor(new Color(40, 90, 200));
    g.fillRect(0, 0, 1200, 800);
    g.setColor(Color.ORANGE);
    g.fillOval(400, 200, 400, 400);
    g.dispose();
    ByteArrayOutputStream plain = new ByteArrayOutputStream();
    ImageIO.write(img, "jpg", plain);
    byte[] jpg = plain.toByteArray();
    byte[] payload = ("Exif\0\0MM\0*\0\0\0\b" + SECRET).getBytes(StandardCharsets.ISO_8859_1);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(jpg, 0, 2); // SOI
    out.write(0xFF);
    out.write(0xE1);
    int len = payload.length + 2;
    out.write(len >> 8);
    out.write(len & 0xff);
    out.write(payload);
    out.write(jpg, 2, jpg.length - 2);
    return out.toByteArray();
  }

  @Test
  void avatarIsResizedReencodedAndStripped() throws IOException {
    long g = newGroup("Аватары");
    TestUser u = newUser(g, "student");
    byte[] input = jpegWithExif();
    assertThat(new String(input, StandardCharsets.ISO_8859_1)).contains(SECRET);

    var r = u.api().putRaw("/api/me/avatar", input);
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    String id = r.json().get("avatar").asString();
    assertThat(u.api().get("/api/me").json().get("user").get("avatar").asString()).isEqualTo(id);

    for (int size : new int[] {64, 256}) {
      var dl = u.api().download("/api/avatars/" + id + "-" + size + ".webp");
      assertThat(dl.statusCode()).isEqualTo(200);
      assertThat(dl.headers().firstValue("Cache-Control").orElseThrow()).contains("immutable");
      byte[] webp = dl.body();
      assertThat(new String(webp, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
      assertThat(new String(webp, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WEBP");
      String raw = new String(webp, StandardCharsets.ISO_8859_1);
      assertThat(raw).doesNotContain(SECRET).doesNotContain("Exif").doesNotContain("EXIF");
      BufferedImage img = ImageIO.read(new ByteArrayInputStream(webp));
      assertThat(img.getWidth()).isEqualTo(size);
      assertThat(img.getHeight()).isEqualTo(size);
    }

    // На диске аватар зашифрован.
    try (Stream<Path> files = Files.list(props.dataDir().resolve("avatars"))) {
      for (Path p : files.toList()) {
        assertThat(new String(Files.readAllBytes(p), 0, 4, StandardCharsets.US_ASCII))
            .isEqualTo("GBF1");
      }
    }

    // Замена удаляет старый файл; без входа аватар не отдаётся.
    String second = u.api().putRaw("/api/me/avatar", input).json().get("avatar").asString();
    assertThat(u.api().download("/api/avatars/" + id + "-64.webp").statusCode()).isEqualTo(404);
    assertThat(client().download("/api/avatars/" + second + "-64.webp").statusCode())
        .isEqualTo(401);
  }

  @Test
  void rejectsNonImagesAndBombs() throws IOException {
    long g = newGroup("Бомбы");
    ApiClient u = newUser(g, "student").api();
    assertThat(u.putRaw("/api/me/avatar", "%PDF-1.4 not an image".getBytes()).status())
        .isEqualTo(415);
    BufferedImage huge = new BufferedImage(9000, 10, BufferedImage.TYPE_BYTE_BINARY);
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    ImageIO.write(huge, "png", bos);
    assertThat(u.putRaw("/api/me/avatar", bos.toByteArray()).status()).isEqualTo(400);
    assertThat(u.download("/api/avatars/..%2F..%2Fsecrets%2Fapp.key").statusCode()).isIn(400, 404);
    assertThat(u.download("/api/avatars/deadbeef-64.webp").statusCode()).isEqualTo(404);
  }

  @Test
  void subjectAndGroupAvatarsRespectPermissions() throws IOException {
    long g = newGroup("Права аватаров");
    TestUser student = newUser(g, "student");
    TestUser headman = newUser(g, "headman");
    long s =
        admin()
            .post("/api/groups/" + g + "/subjects", Map.of("name", "История"))
            .json()
            .get("id")
            .asLong();
    byte[] img = jpegWithExif();
    assertThat(student.api().putRaw("/api/subjects/" + s + "/avatar", img).status()).isEqualTo(403);
    assertThat(headman.api().putRaw("/api/subjects/" + s + "/avatar", img).status()).isEqualTo(200);
    assertThat(headman.api().putRaw("/api/groups/" + g + "/avatar", img).status()).isEqualTo(403);
    assertThat(admin().putRaw("/api/groups/" + g + "/avatar", img).status()).isEqualTo(200);
  }

  @Test
  void subjectCoverIsWideReencodedAndRemovable() throws IOException {
    long g = newGroup("Фоны предметов");
    TestUser student = newUser(g, "student");
    TestUser headman = newUser(g, "headman");
    long s =
        admin()
            .post("/api/groups/" + g + "/subjects", Map.of("name", "Физика"))
            .json()
            .get("id")
            .asLong();
    byte[] img = jpegWithExif();
    assertThat(student.api().putRaw("/api/subjects/" + s + "/cover", img).status()).isEqualTo(403);
    var up = headman.api().putRaw("/api/subjects/" + s + "/cover", img);
    assertThat(up.status()).as(up.body()).isEqualTo(200);
    String cover = up.json().get("cover").asString();

    // Широкая картинка 16:9 в двух размерах, без EXIF; предмет знает свой фон.
    for (int w : new int[] {1280, 480}) {
      var dl = student.api().download("/api/avatars/" + cover + "-" + w + ".webp");
      assertThat(dl.statusCode()).isEqualTo(200);
      byte[] webp = dl.body();
      assertThat(new String(webp, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WEBP");
      assertThat(new String(webp, StandardCharsets.ISO_8859_1)).doesNotContain(SECRET);
      BufferedImage out = ImageIO.read(new ByteArrayInputStream(webp));
      assertThat(out.getWidth()).isEqualTo(Math.min(w, 1200));
      assertThat(out.getWidth() * 9).isEqualTo(out.getHeight() * 16);
    }
    assertThat(student.api().get("/api/subjects/" + s).json().get("cover").asString())
        .isEqualTo(cover);

    // Убрать фон: снова иконка, файлов нет.
    assertThat(headman.api().delete("/api/subjects/" + s + "/cover").status()).isEqualTo(200);
    assertThat(student.api().get("/api/subjects/" + s).json().get("cover").isNull()).isTrue();
    assertThat(student.api().download("/api/avatars/" + cover + "-480.webp").statusCode())
        .isEqualTo(404);
  }
}
