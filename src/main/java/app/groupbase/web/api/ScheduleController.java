package app.groupbase.web.api;

import app.groupbase.auth.Actor;
import app.groupbase.auth.Permission;
import app.groupbase.schedule.LessonService;
import app.groupbase.web.Require;
import java.util.List;
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

/**
 * Расписание пар: смотреть — всем в группе, вести (файл календаря, пары вручную) — MANAGE_SCHEDULE.
 */
@RestController
class ScheduleController {

  private final LessonService lessons;

  ScheduleController(LessonService lessons) {
    this.lessons = lessons;
  }

  @GetMapping("/api/schedule")
  List<LessonService.Lesson> list(
      Actor actor,
      @RequestParam(required = false) Long group,
      @RequestParam(required = false) Long subject,
      @RequestParam(required = false) Long from,
      @RequestParam(required = false) Long to) {
    return lessons.list(actor, group, subject, from, to);
  }

  @GetMapping("/api/lessons/{id}")
  LessonService.Detail get(Actor actor, @PathVariable long id) {
    return lessons.get(actor, id);
  }

  @Require(Permission.MANAGE_SCHEDULE)
  @PostMapping("/api/groups/{groupId}/lessons")
  List<LessonService.Lesson> create(
      Actor actor, @PathVariable long groupId, @RequestBody LessonService.Input in) {
    return lessons.create(actor, groupId, in);
  }

  @PatchMapping("/api/lessons/{id}")
  LessonService.Lesson update(
      Actor actor, @PathVariable long id, @RequestBody LessonService.Input in) {
    return lessons.update(actor, id, in);
  }

  record Flag(Boolean value) {}

  @PutMapping("/api/lessons/{id}/cancelled")
  LessonService.Lesson cancelled(Actor actor, @PathVariable long id, @RequestBody Flag in) {
    return lessons.setCancelled(actor, id, Boolean.TRUE.equals(in.value()));
  }

  @DeleteMapping("/api/lessons/{id}")
  Map<String, String> delete(Actor actor, @PathVariable long id) {
    lessons.delete(actor, id);
    return Map.of("status", "ok");
  }

  @Require(Permission.MANAGE_SCHEDULE)
  @PostMapping("/api/groups/{groupId}/schedule/preview")
  LessonService.Preview preview(
      Actor actor, @PathVariable long groupId, @RequestBody LessonService.IcsInput in) {
    return lessons.preview(actor, groupId, in.ics());
  }

  @Require(Permission.MANAGE_SCHEDULE)
  @PostMapping("/api/groups/{groupId}/schedule/import")
  LessonService.Imported importIcs(
      Actor actor, @PathVariable long groupId, @RequestBody LessonService.ImportInput in) {
    return lessons.importIcs(actor, groupId, in);
  }

  @Require(Permission.MANAGE_SCHEDULE)
  @DeleteMapping("/api/groups/{groupId}/schedule")
  Map<String, Integer> clear(Actor actor, @PathVariable long groupId) {
    return Map.of("deleted", lessons.clear(actor, groupId));
  }
}
