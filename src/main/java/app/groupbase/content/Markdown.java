package app.groupbase.content;

import java.util.List;
import java.util.regex.Pattern;
import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;

/**
 * Markdown → HTML на сервере, затем санитизация. Сырой HTML в тексте экранируется. Картинки
 * разрешены только со своего сервера: внешняя картинка — это трекер, который раскрывает IP
 * читателей.
 */
public final class Markdown {

  public static final int MAX_LENGTH = 20_000;

  private static final List<Extension> EXTENSIONS =
      List.of(
          TablesExtension.create(), StrikethroughExtension.create(), AutolinkExtension.create());
  private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
  private static final HtmlRenderer RENDERER =
      HtmlRenderer.builder().extensions(EXTENSIONS).escapeHtml(true).softbreak("<br>").build();

  private static final Pattern LOCAL_IMAGE =
      Pattern.compile("^/api/files/[0-9]+(\\?[a-z=&0-9]*)?$");

  private static final PolicyFactory POLICY =
      Sanitizers.FORMATTING
          .and(Sanitizers.BLOCKS)
          .and(Sanitizers.TABLES)
          .and(
              new HtmlPolicyBuilder()
                  .allowElements("a")
                  .allowAttributes("href")
                  .onElements("a")
                  .allowUrlProtocols("http", "https", "mailto")
                  .requireRelsOnLinks("noopener", "noreferrer", "nofollow")
                  .allowElements("pre", "code", "hr", "br", "del", "s")
                  // img без разрешённого src (внешняя картинка) удаляется целиком.
                  .allowElements(
                      (name, attrs) -> attrs.indexOf("src") % 2 == 0 ? name : null, "img")
                  .allowAttributes("src")
                  .matching(LOCAL_IMAGE)
                  .onElements("img")
                  .allowAttributes("alt", "title")
                  .onElements("img")
                  .toFactory());

  private Markdown() {}

  public static String render(String markdown) {
    if (markdown == null || markdown.isBlank()) {
      return "";
    }
    String html = RENDERER.render(PARSER.parse(markdown));
    return POLICY.sanitize(html);
  }
}
