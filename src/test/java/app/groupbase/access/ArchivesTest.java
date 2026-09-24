package app.groupbase.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchivesTest {

  /** Минимальный tar: заголовок 512 байт (имя, размер в восьмеричном виде, тип '0') и данные. */
  private static byte[] tarGz(String name, byte[] data) throws IOException {
    ByteArrayOutputStream tar = new ByteArrayOutputStream();
    byte[] header = new byte[512];
    byte[] n = name.getBytes(StandardCharsets.UTF_8);
    System.arraycopy(n, 0, header, 0, n.length);
    byte[] size = String.format("%011o", data.length).getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(size, 0, header, 124, size.length);
    header[156] = '0';
    tar.write(header);
    tar.write(data);
    tar.write(new byte[(512 - data.length % 512) % 512]);
    tar.write(new byte[1024]);
    ByteArrayOutputStream gz = new ByteArrayOutputStream();
    try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
      out.write(tar.toByteArray());
    }
    return gz.toByteArray();
  }

  @Test
  void extractsFromTarGz(@TempDir Path dir) throws IOException {
    byte[] data = "#!/bin/sh\necho clo\n".repeat(100).getBytes(StandardCharsets.UTF_8);
    Path out = dir.resolve("clo");
    Archives.extractTarGz(new ByteArrayInputStream(tarGz("./clo", data)), "clo", out);
    assertThat(Files.readAllBytes(out)).isEqualTo(data);
    assertThatThrownBy(
            () ->
                Archives.extractTarGz(
                    new ByteArrayInputStream(tarGz("other", data)), "clo", dir.resolve("x")))
        .hasMessageContaining("нет clo");
  }

  @Test
  void extractsFromZip(@TempDir Path dir) throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(buf)) {
      zip.putNextEntry(new ZipEntry("readme.txt"));
      zip.write("x".getBytes(StandardCharsets.UTF_8));
      zip.putNextEntry(new ZipEntry("bin/clo.exe"));
      zip.write("MZ-program".getBytes(StandardCharsets.UTF_8));
    }
    Path out = dir.resolve("clo.exe");
    Archives.extractZip(new ByteArrayInputStream(buf.toByteArray()), "clo.exe", out);
    assertThat(Files.readString(out)).isEqualTo("MZ-program");
  }

  @Test
  void cloudpubAddressIsFoundInClientOutput() {
    assertThat(
            CloudPub.findUrl(
                "Сервис опубликован: [groupbase] http://127.0.0.1:17380 ->"
                    + " https://wildly-suitable-fish.cloudpub.ru:443/\n"))
        .isEqualTo("https://wildly-suitable-fish.cloudpub.ru");
    assertThat(CloudPub.findUrl("Ошибка публикации: [groupbase] http://127.0.0.1:1 -> timeout"))
        .isNull();
  }
}
