package app.groupbase.web.api;

import app.groupbase.accounts.InviteService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.GroupRole;
import app.groupbase.auth.Permission;
import app.groupbase.store.Invite;
import app.groupbase.web.Public;
import app.groupbase.web.Require;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class InviteController {

  record CreateBody(GroupRole role, Integer maxUses, Integer ttlHours, String note) {}

  record AcceptBody(String username, String displayName, String password) {}

  private final InviteService invites;
  private final Http http;

  InviteController(InviteService invites, Http http) {
    this.invites = invites;
    this.http = http;
  }

  @Require(Permission.CREATE_INVITES)
  @GetMapping("/api/groups/{groupId}/invites")
  List<Invite> list(@PathVariable long groupId) {
    return invites.list(groupId);
  }

  @Require(Permission.CREATE_INVITES)
  @PostMapping("/api/groups/{groupId}/invites")
  InviteService.Created create(Actor actor, @PathVariable long groupId, @RequestBody CreateBody b) {
    return invites.create(
        actor,
        groupId,
        b.role() == null ? GroupRole.STUDENT : b.role(),
        b.maxUses(),
        b.ttlHours() == null ? 24 * 7 : b.ttlHours(),
        b.note());
  }

  @Require(Permission.CREATE_INVITES)
  @DeleteMapping("/api/groups/{groupId}/invites/{inviteId}")
  Map<String, String> revoke(Actor actor, @PathVariable long groupId, @PathVariable long inviteId) {
    invites.revoke(actor, groupId, inviteId);
    return Map.of("status", "ok");
  }

  @Public
  @GetMapping("/api/invites/{token}")
  InviteService.Info info(@PathVariable String token) {
    return invites.info(token);
  }

  @Public
  @PostMapping("/api/invites/{token}/accept")
  Map<String, String> accept(
      @PathVariable String token, @RequestBody AcceptBody b, HttpServletResponse res) {
    return http.startSession(
        invites.accept(token, b.username(), b.displayName(), b.password()), res);
  }

  @PostMapping("/api/invites/{token}/join")
  Map<String, Object> join(Actor actor, @PathVariable String token) {
    InviteService.Joined j = invites.join(actor, token);
    return Map.of("status", j.already() ? "already" : "ok", "groupId", j.groupId());
  }
}
