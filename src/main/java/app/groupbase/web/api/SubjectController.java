package app.groupbase.web.api;

import app.groupbase.auth.Actor;
import app.groupbase.auth.Permission;
import app.groupbase.content.SubjectService;
import app.groupbase.content.SubjectStore;
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

@RestController
class SubjectController {

  record LinkBody(Long groupId) {}

  record FlagBody(Boolean value) {}

  private final SubjectService subjects;

  SubjectController(SubjectService subjects) {
    this.subjects = subjects;
  }

  @GetMapping("/api/subjects")
  List<SubjectService.SubjectView> list(Actor actor, @RequestParam(required = false) Long group) {
    return subjects.list(actor, group);
  }

  @Require(Permission.MANAGE_SUBJECTS)
  @PostMapping("/api/groups/{groupId}/subjects")
  SubjectService.SubjectView create(
      Actor actor, @PathVariable long groupId, @RequestBody SubjectService.Input in) {
    return subjects.create(actor, groupId, in);
  }

  @GetMapping("/api/subjects/{id}")
  SubjectService.SubjectView get(Actor actor, @PathVariable long id) {
    return subjects.get(actor, id);
  }

  @PatchMapping("/api/subjects/{id}")
  SubjectService.SubjectView update(
      Actor actor, @PathVariable long id, @RequestBody SubjectService.Input in) {
    return subjects.update(actor, id, in);
  }

  @PutMapping("/api/subjects/{id}/archived")
  Map<String, String> archive(Actor actor, @PathVariable long id, @RequestBody FlagBody b) {
    subjects.setArchived(actor, id, Boolean.TRUE.equals(b.value()));
    return Map.of("status", "ok");
  }

  @PutMapping("/api/subjects/{id}/pinned")
  Map<String, String> pin(Actor actor, @PathVariable long id, @RequestBody FlagBody b) {
    subjects.setPinned(actor, id, Boolean.TRUE.equals(b.value()));
    return Map.of("status", "ok");
  }

  /** «Не мой предмет» (другая подгруппа): value=false — скрыть у себя, true — вернуть. */
  @PutMapping("/api/subjects/{id}/mine")
  Map<String, String> mine(Actor actor, @PathVariable long id, @RequestBody FlagBody b) {
    subjects.setMine(actor, id, !Boolean.FALSE.equals(b.value()));
    return Map.of("status", "ok");
  }

  @PostMapping("/api/subjects/{id}/links")
  SubjectService.LinkResult link(Actor actor, @PathVariable long id, @RequestBody LinkBody b) {
    if (b.groupId() == null) {
      throw app.groupbase.web.ApiException.invalid("groupId", "Выберите группу");
    }
    return subjects.link(actor, id, b.groupId());
  }

  @DeleteMapping("/api/subjects/{id}/links/{group}")
  Map<String, String> unlink(Actor actor, @PathVariable long id, @PathVariable long group) {
    subjects.unlink(actor, id, group);
    return Map.of("status", "ok");
  }

  @GetMapping("/api/link-requests")
  List<SubjectStore.LinkRequest> requests(Actor actor) {
    return subjects.requests(actor);
  }

  @PostMapping("/api/link-requests/{id}/accept")
  Map<String, String> accept(Actor actor, @PathVariable long id) {
    subjects.decide(actor, id, true);
    return Map.of("status", "ok");
  }

  @PostMapping("/api/link-requests/{id}/reject")
  Map<String, String> reject(Actor actor, @PathVariable long id) {
    subjects.decide(actor, id, false);
    return Map.of("status", "ok");
  }
}
