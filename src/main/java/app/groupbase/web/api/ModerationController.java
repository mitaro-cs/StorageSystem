package app.groupbase.web.api;

import app.groupbase.auth.Actor;
import app.groupbase.moderation.ModerationService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * «Модерация» и жалобы. Права проверяет сервис: раздел открыт тем, у кого есть право модерировать
 * хотя бы в одной группе (группы здесь не в пути — они у каждой записи свои), а пожаловаться может
 * каждый, кому запись видна.
 */
@RestController
class ModerationController {

  record ReportBody(String type, Long id, String reason) {}

  record ResolveBody(String type, Long id, String action) {}

  private final ModerationService moderation;

  ModerationController(ModerationService moderation) {
    this.moderation = moderation;
  }

  @GetMapping("/api/moderation/summary")
  ModerationService.Summary summary(Actor actor) {
    return moderation.summary(actor);
  }

  @GetMapping("/api/moderation/reports")
  List<ModerationService.Report> reports(Actor actor) {
    return moderation.reports(actor);
  }

  @PostMapping("/api/moderation/reports/resolve")
  Map<String, String> resolve(Actor actor, @RequestBody ResolveBody b) {
    moderation.resolve(actor, b.type(), b.id() == null ? 0 : b.id(), b.action());
    return Map.of("status", "ok");
  }

  @GetMapping("/api/moderation/comments")
  List<ModerationService.Target> comments(
      Actor actor, @RequestParam(required = false) Long before) {
    return moderation.comments(actor, before);
  }

  @GetMapping("/api/moderation/hidden")
  List<ModerationService.Target> hidden(Actor actor) {
    return moderation.hidden(actor);
  }

  @GetMapping("/api/moderation/log")
  List<ModerationService.LogEntry> log(Actor actor, @RequestParam(required = false) Long before) {
    return moderation.log(actor, before);
  }

  @PostMapping("/api/reports")
  Map<String, String> report(Actor actor, @RequestBody ReportBody b) {
    moderation.report(actor, b.type(), b.id() == null ? 0 : b.id(), b.reason());
    return Map.of("status", "ok");
  }
}
