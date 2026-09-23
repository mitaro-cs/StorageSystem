package app.groupbase.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarkdownTest {

  @Test
  void rendersBasicFormatting() {
    String html = Markdown.render("**жирный** и *курсив*\n\n- раз\n- два\n\n`код`");
    assertThat(html)
        .contains("<strong>жирный</strong>")
        .contains("<em>курсив</em>")
        .contains("<li>раз</li>")
        .contains("<code>код</code>");
  }

  @Test
  void rawHtmlAndScriptsAreNeutralized() {
    String html = Markdown.render("<script>alert(1)</script><img src=x onerror=alert(1)>");
    // Сырой HTML выводится как текст: тегов нет, только экранированные символы.
    assertThat(html).doesNotContain("<script").doesNotContain("<img").contains("&lt;script&gt;");
  }

  @Test
  void javascriptLinksAreDropped() {
    assertThat(Markdown.render("[клик](javascript:alert(1))")).doesNotContain("javascript:");
  }

  @Test
  void linksGetSafeRel() {
    String html = Markdown.render("[сайт](https://mtuci.ru)");
    assertThat(html)
        .contains("href=\"https://mtuci.ru\"")
        .contains("noopener")
        .contains("noreferrer");
  }

  @Test
  void externalImagesAreRemovedButLocalAllowed() {
    assertThat(Markdown.render("![трекер](https://evil.example/pixel.gif)")).doesNotContain("<img");
    assertThat(Markdown.render("![схема](/api/files/12)")).contains("<img src=\"/api/files/12\"");
  }

  @Test
  void tablesAndStrikethrough() {
    String html = Markdown.render("| a | b |\n|---|---|\n| 1 | 2 |\n\n~~нет~~");
    assertThat(html).contains("<table>").contains("<td>1</td>").contains("<del>нет</del>");
  }
}
