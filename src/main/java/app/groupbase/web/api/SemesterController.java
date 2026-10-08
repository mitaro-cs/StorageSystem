package app.groupbase.web.api;

import app.groupbase.auth.Actor;
import app.groupbase.auth.Permission;
import app.groupbase.content.SemesterService;
import app.groupbase.web.Require;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Архивы прошлых семестров. */
@RestController
class SemesterController {

  record NameBody(String name) {}

  private final SemesterService semesters;

  SemesterController(SemesterService semesters) {
    this.semesters = semesters;
  }

  /** Архивы группы и название по умолчанию для нового. */
  @GetMapping("/api/groups/{groupId}/semesters")
  Map<String, Object> list(Actor actor, @PathVariable long groupId) {
    return Map.of("items", semesters.list(actor, groupId), "suggested", semesters.suggestedName());
  }

  @Require(Permission.MANAGE_SUBJECTS)
  @PostMapping("/api/groups/{groupId}/semesters")
  SemesterService.SemesterView create(
      Actor actor, @PathVariable long groupId, @RequestBody SemesterService.Input in) {
    return semesters.create(actor, groupId, in);
  }

  @PatchMapping("/api/semesters/{id}")
  Map<String, String> rename(Actor actor, @PathVariable long id, @RequestBody NameBody b) {
    semesters.rename(actor, id, b.name());
    return Map.of("status", "ok");
  }

  /** Расформировать: предметы возвращаются в текущие. */
  @DeleteMapping("/api/semesters/{id}")
  Map<String, String> restore(Actor actor, @PathVariable long id) {
    semesters.restore(actor, id);
    return Map.of("status", "ok");
  }
}
