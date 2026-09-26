package app.groupbase.web.api;

import app.groupbase.accounts.AccountService;
import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Permission;
import app.groupbase.avatars.AvatarService;
import app.groupbase.content.Access;
import app.groupbase.content.SubjectService;
import app.groupbase.content.SubjectStore;
import app.groupbase.store.GroupStore;
import app.groupbase.store.UserStore;
import app.groupbase.web.ApiException;
import app.groupbase.web.Require;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/** Загрузка (сырым телом) и выдача аватаров. Видны только вошедшим участникам инстанса. */
@RestController
class AvatarController {

  private final AvatarService avatars;
  private final UserStore users;
  private final GroupStore groups;
  private final SubjectStore subjects;
  private final SubjectService subjectService;
  private final Access access;
  private final AuditService audit;

  AvatarController(
      AvatarService avatars,
      UserStore users,
      GroupStore groups,
      SubjectStore subjects,
      SubjectService subjectService,
      Access access,
      AuditService audit) {
    this.avatars = avatars;
    this.users = users;
    this.groups = groups;
    this.subjects = subjects;
    this.subjectService = subjectService;
    this.access = access;
    this.audit = audit;
  }

  @GetMapping("/api/avatars/{name}")
  void get(@PathVariable String name, HttpServletResponse res) throws IOException {
    try (InputStream in = avatars.open(name)) {
      if (in == null) {
        throw ApiException.notFound();
      }
      res.setContentType("image/webp");
      res.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable");
      try (OutputStream out = res.getOutputStream()) {
        in.transferTo(out);
      }
    }
  }

  @PutMapping("/api/me/avatar")
  Map<String, String> setMine(Actor actor, HttpServletRequest req) throws IOException {
    String old = users.find(actor.id()).orElseThrow().avatar();
    String id = avatars.store(req.getInputStream());
    users.setAvatar(actor.id(), id);
    avatars.delete(old);
    return Map.of("avatar", id);
  }

  @DeleteMapping("/api/me/avatar")
  Map<String, String> deleteMine(Actor actor) {
    String old = users.find(actor.id()).orElseThrow().avatar();
    users.setAvatar(actor.id(), null);
    avatars.delete(old);
    return Map.of("status", "ok");
  }

  @Require(Permission.MANAGE_INSTANCE)
  @PutMapping("/api/groups/{groupId}/avatar")
  Map<String, String> setGroup(Actor actor, @PathVariable long groupId, HttpServletRequest req)
      throws IOException {
    String old = groups.find(groupId).orElseThrow(ApiException::notFound).avatar();
    String id = avatars.store(req.getInputStream());
    groups.setAvatar(groupId, id);
    avatars.delete(old);
    audit.log(actor, groupId, "group.avatar", "group", groupId);
    return Map.of("avatar", id);
  }

  @PutMapping("/api/subjects/{subjectId}/avatar")
  Map<String, String> setSubject(Actor actor, @PathVariable long subjectId, HttpServletRequest req)
      throws IOException {
    SubjectStore.Row s = subjectService.visible(actor, subjectId);
    if (!access.can(actor, Permission.MANAGE_SUBJECTS, subjects.groupIds(subjectId))) {
      throw ApiException.forbidden();
    }
    String id = avatars.store(req.getInputStream());
    subjects.setAvatar(subjectId, id);
    avatars.delete(s.avatar());
    return Map.of("avatar", id);
  }

  /** Фон карточки предмета — широкая картинка вместо иконки. */
  @PutMapping("/api/subjects/{subjectId}/cover")
  Map<String, String> setCover(Actor actor, @PathVariable long subjectId, HttpServletRequest req)
      throws IOException {
    SubjectStore.Row s = manageable(actor, subjectId);
    String id = avatars.storeCover(req.getInputStream());
    subjects.setCover(subjectId, id);
    avatars.delete(s.cover());
    return Map.of("cover", id);
  }

  @DeleteMapping("/api/subjects/{subjectId}/cover")
  Map<String, String> removeCover(Actor actor, @PathVariable long subjectId) {
    SubjectStore.Row s = manageable(actor, subjectId);
    subjects.setCover(subjectId, null);
    avatars.delete(s.cover());
    return Map.of("status", "ok");
  }

  private SubjectStore.Row manageable(Actor actor, long subjectId) {
    SubjectStore.Row s = subjectService.visible(actor, subjectId);
    if (!access.can(actor, Permission.MANAGE_SUBJECTS, subjects.groupIds(subjectId))) {
      throw ApiException.forbidden();
    }
    return s;
  }

  /** При удалении аккаунта удаляется и аватар. */
  @EventListener
  void onUserDeleted(AccountService.UserDeleted e) {
    avatars.delete(e.avatar());
  }
}
