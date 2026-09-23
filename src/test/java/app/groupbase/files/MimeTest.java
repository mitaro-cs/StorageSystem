package app.groupbase.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class MimeTest {

  @Test
  void detectsByContentNotByName() {
    assertThat(Mime.detect("%PDF-1.7\n".getBytes(), "photo.jpg")).isEqualTo("application/pdf");
    byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};
    assertThat(Mime.detect(png, "doc.pdf")).isEqualTo("image/png");
  }

  @Test
  void executablesAreBlocked() {
    byte[] elf = {0x7f, 'E', 'L', 'F', 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    assertThat(Mime.blocked(Mime.detect(elf, "notes.txt"))).isTrue();
    byte[] mz = new byte[128];
    mz[0] = 'M';
    mz[1] = 'Z';
    assertThat(Mime.blocked(Mime.detect(mz, "lab.pdf"))).isTrue();
  }

  @Test
  void officeDocumentsUseNameInsideZipFamily() throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bos)) {
      zip.putNextEntry(new ZipEntry("word/document.xml"));
      zip.write("<w/>".getBytes());
    }
    String t = Mime.detect(bos.toByteArray(), "Лаба.docx");
    assertThat(t)
        .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    assertThat(Mime.inline(t)).isFalse();
  }

  @Test
  void safeNames() {
    assertThat(Mime.safeName("../../etc/passwd")).isEqualTo("passwd");
    assertThat(Mime.safeName("C:\\Users\\me\\Лаба 1.pdf")).isEqualTo("Лаба 1.pdf");
    assertThat(Mime.safeName("a\u0000b<c>.txt")).isEqualTo("abc.txt");
    assertThat(Mime.safeName("..")).isEqualTo("file");
    assertThat(Mime.safeName("x".repeat(300) + ".pdf")).hasSize(200).endsWith(".pdf");
  }

  @Test
  void svgIsNeverInline() {
    assertThat(Mime.inline("image/svg+xml")).isFalse();
    assertThat(Mime.inline("text/html")).isFalse();
    assertThat(Mime.inline("application/pdf")).isTrue();
  }
}
