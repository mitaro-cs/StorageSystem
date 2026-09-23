package app.groupbase.web.api;

import app.groupbase.auth.Actor;
import app.groupbase.auth.Permission;
import app.groupbase.export.ExportService;
import app.groupbase.web.Require;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Скачивание архивов: свои данные и группа целиком. Пишется потоком, без временных файлов. */
@RestController
class ExportController {

  private final ExportService exports;
  private final Clock clock;

  ExportController(ExportService exports, Clock clock) {
    this.exports = exports;
    this.clock = clock;
  }

  @GetMapping("/api/me/export")
  void me(Actor actor, HttpServletResponse res) throws IOException {
    zip(res, "groupbase-мои-данные-" + exports.day(clock.millis()) + ".zip");
    exports.user(actor, res.getOutputStream());
  }

  @Require(Permission.EXPORT_GROUP)
  @GetMapping("/api/groups/{groupId}/export")
  void group(Actor actor, @PathVariable long groupId, HttpServletResponse res) throws IOException {
    String name = exports.groupName(groupId).replaceAll("[\\\\/:*?\"<>|]", "_");
    zip(res, name + "-архив-" + exports.day(clock.millis()) + ".zip");
    exports.group(actor, groupId, res.getOutputStream());
  }

  private static void zip(HttpServletResponse res, String filename) {
    res.setContentType("application/zip");
    res.setHeader(
        HttpHeaders.CONTENT_DISPOSITION,
        ContentDisposition.attachment()
            .filename(filename, StandardCharsets.UTF_8)
            .build()
            .toString());
    res.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
  }
}
