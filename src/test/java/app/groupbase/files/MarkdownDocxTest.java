package app.groupbase.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;

class MarkdownDocxTest {

  private static Map<String, String> docx(String md) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    MarkdownDocx.write(md, "Лекция", out);
    Map<String, String> parts = new HashMap<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
      for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
        parts.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    // Каждая часть — правильный XML, иначе Word откажется открывать.
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    for (String xml : parts.values()) {
      factory
          .newDocumentBuilder()
          .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
    return parts;
  }

  @Test
  void lectureNotesBecomeAWordDocument() throws Exception {
    var parts =
        docx(
            """
            # Производные

            Определение: **предел** отношения, *см. учебник* и ~~не это~~.

            - первое
              - вложенное
            1. шаг один
            2. шаг два

            > Важно: не делить на ноль & не путать <знаки>.

            ```
            f(x) = x^2
            ```

            | Функция | Производная |
            |---|---|
            | x^2 | 2x |

            [Подробнее](https://example.org/derivative)
            """);
    assertThat(parts)
        .containsKeys(
            "[Content_Types].xml",
            "_rels/.rels",
            "word/document.xml",
            "word/styles.xml",
            "word/_rels/document.xml.rels");
    String doc = parts.get("word/document.xml");
    assertThat(doc).contains("w:val=\"Heading1\"", "Производные");
    assertThat(doc).contains("<w:b/>", "<w:i/>", "<w:strike/>");
    assertThat(doc).contains("•", "1.", "2.", "w:val=\"ListParagraph\"");
    assertThat(doc).contains("w:val=\"Quote\"", "&amp; не путать &lt;знаки&gt;");
    assertThat(doc).contains("w:val=\"Code\"", "f(x) = x^2");
    assertThat(doc).contains("<w:tbl>", "Производная", "2x");
    assertThat(doc).contains("<w:hyperlink r:id=\"rIdLink1\">");
    assertThat(parts.get("word/_rels/document.xml.rels"))
        .contains("https://example.org/derivative", "TargetMode=\"External\"");
  }

  @Test
  void onlyWebLinksBecomeHyperlinks() throws Exception {
    String doc =
        docx("[локально](javascript:alert(1)) и [почта](mailto:a@b.ru)").get("word/document.xml");
    assertThat(doc).doesNotContain("javascript");
    assertThat(doc).contains("rIdLink1").doesNotContain("rIdLink2");
  }

  @Test
  void recognizesMarkdownByTypeOrName() {
    assertThat(MarkdownDocx.isMarkdown("text/x-web-markdown", "a.txt")).isTrue();
    assertThat(MarkdownDocx.isMarkdown("text/plain", "Конспект.MD")).isTrue();
    assertThat(MarkdownDocx.isMarkdown("application/pdf", "a.pdf")).isFalse();
  }
}
