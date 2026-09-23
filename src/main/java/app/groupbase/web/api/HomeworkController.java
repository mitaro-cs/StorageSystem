package app.groupbase.web.api;

import app.groupbase.auth.Actor;
import app.groupbase.content.CommentService;
import app.groupbase.content.HomeworkService;
import app.groupbase.web.ApiException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HomeworkController {

  record FlagBody(Boolean value) {}

  record CommentBody(String body) {}

  private final HomeworkService homework;
  private final CommentService comments;

  HomeworkController(HomeworkService homework, CommentService comments) {
    this.homework = homework;
    this.comments = comments;
  }

  @GetMapping("/api/homework")
  List<HomeworkService.Item> list(
      Actor actor,
      @RequestParam(defaultValue = "week") String view,
      @RequestParam(required = false) Long group,
      @RequestParam(required = false) Long subject,
      @RequestParam(required = false) Long from,
      @RequestParam(required = false) Long to,
      @RequestParam(required = false) Integer limit) {
    HomeworkService.View v;
    try {
      v = HomeworkService.View.valueOf(view.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw ApiException.badRequest("Неизвестное представление: " + view);
    }
    return homework.list(actor, v, group, subject, from, to, limit);
  }

  @PostMapping("/api/homework")
  HomeworkService.Item create(Actor actor, @RequestBody HomeworkService.Input in) {
    return homework.create(actor, in);
  }

  @GetMapping("/api/homework/{id}")
  HomeworkService.Item get(Actor actor, @PathVariable long id) {
    return homework.get(actor, id);
  }

  @PatchMapping("/api/homework/{id}")
  HomeworkService.Item update(
      Actor actor, @PathVariable long id, @RequestBody HomeworkService.Input in) {
    return homework.update(actor, id, in);
  }

  @DeleteMapping("/api/homework/{id}")
  Map<String, String> delete(Actor actor, @PathVariable long id) {
    homework.delete(actor, id);
    return Map.of("status", "ok");
  }

  @PutMapping("/api/homework/{id}/hidden")
  Map<String, String> hide(Actor actor, @PathVariable long id, @RequestBody FlagBody b) {
    homework.setHidden(actor, id, Boolean.TRUE.equals(b.value()));
    return Map.of("status", "ok");
  }

  @PutMapping("/api/homework/{id}/done")
  Map<String, String> done(Actor actor, @PathVariable long id, @RequestBody FlagBody b) {
    homework.setDone(actor, id, Boolean.TRUE.equals(b.value()));
    return Map.of("status", "ok");
  }

  @GetMapping("/api/homework/{id}/comments")
  List<CommentService.Comment> comments(Actor actor, @PathVariable long id) {
    return comments.list(actor, CommentService.Parent.HOMEWORK, id);
  }

  @PostMapping("/api/homework/{id}/comments")
  CommentService.Comment comment(Actor actor, @PathVariable long id, @RequestBody CommentBody b) {
    return comments.add(actor, CommentService.Parent.HOMEWORK, id, b.body());
  }
}
