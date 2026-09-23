package app.groupbase.web.api;

import app.groupbase.content.Markdown;
import app.groupbase.web.ApiException;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Предпросмотр Markdown тем же рендером и санитайзером, что и при сохранении. */
@RestController
class MarkdownController {

  record Body(String text) {}

  @PostMapping("/api/markdown")
  Map<String, String> render(@RequestBody Body b) {
    String text = b.text() == null ? "" : b.text();
    if (text.length() > Markdown.MAX_LENGTH) {
      throw ApiException.invalid("text", "Текст слишком длинный");
    }
    return Map.of("html", Markdown.render(text));
  }
}
