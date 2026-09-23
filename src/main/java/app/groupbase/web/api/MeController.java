package app.groupbase.web.api;

import app.groupbase.accounts.AccountService;
import app.groupbase.accounts.InstanceSettings;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Authz;
import app.groupbase.auth.GroupRole;
import app.groupbase.auth.InstanceRole;
import app.groupbase.auth.Permission;
import app.groupbase.auth.TotpService;
import app.groupbase.cli.Main;
import app.groupbase.config.GroupbaseProperties;
import app.groupbase.store.Group;
import app.groupbase.store.GroupStore;
import app.groupbase.store.User;
import app.groupbase.store.UserStore;
import app.groupbase.web.AllowRestricted;
import app.groupbase.web.ApiException;
import app.groupbase.web.Cookies;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
class MeController {

  record UserView(
      long id,
      String username,
      String displayName,
      String avatar,
      InstanceRole instanceRole,
      boolean totpEnabled) {}

  record GroupView(
      long id,
      String slug,
      String name,
      String university,
      Integer course,
      String avatar,
      boolean archived,
      GroupRole role,
      Set<Permission> permissions) {}

  record InstanceView(String name, String mode, String version, boolean requireStaffTotp) {}

  record MeView(
      UserView user,
      String restriction,
      InstanceView instance,
      List<GroupView> groups,
      Set<Permission> permissions) {}

  record ProfileBody(String displayName) {}

  record PasswordBody(String current, String password) {}

  record CodeBody(String code) {}

  record DeleteBody(String password) {}

  private final UserStore users;
  private final GroupStore groups;
  private final Authz authz;
  private final InstanceSettings settings;
  private final AccountService accounts;
  private final TotpService totp;
  private final Cookies cookies;
  private final boolean requireStaffTotp;

  MeController(
      UserStore users,
      GroupStore groups,
      Authz authz,
      InstanceSettings settings,
      AccountService accounts,
      TotpService totp,
      Cookies cookies,
      GroupbaseProperties props) {
    this.users = users;
    this.groups = groups;
    this.authz = authz;
    this.settings = settings;
    this.accounts = accounts;
    this.totp = totp;
    this.cookies = cookies;
    this.requireStaffTotp = props.auth().requireStaffTotp();
  }

  @AllowRestricted
  @GetMapping
  MeView me(Actor actor) {
    User u = users.find(actor.id()).orElseThrow(ApiException::unauthorized);
    Map<Long, GroupRole> roles = groups.rolesOf(actor.id());
    List<GroupView> gs =
        actor.restriction() != null
            ? List.of()
            : groups.listByIds(authz.visibleGroupIds(actor)).stream()
                .map(g -> view(g, roles.get(g.id()), actor))
                .toList();
    String name = settings.name();
    return new MeView(
        new UserView(
            u.id(), u.username(), u.displayName(), u.avatar(), u.instanceRole(), u.totpEnabled()),
        actor.restriction() == null ? null : actor.restriction().id(),
        new InstanceView(name, settings.mode().id(), Main.version(), requireStaffTotp),
        gs,
        authz.instancePermissions(actor));
  }

  private GroupView view(Group g, GroupRole role, Actor actor) {
    return new GroupView(
        g.id(),
        g.slug(),
        g.name(),
        g.university(),
        g.course(),
        g.avatar(),
        g.archivedAt() != null,
        role,
        authz.permissions(actor, g.id()));
  }

  @PatchMapping
  Map<String, String> update(Actor actor, @RequestBody ProfileBody b) {
    accounts.rename(actor, b.displayName());
    return Map.of("status", "ok");
  }

  @AllowRestricted
  @PostMapping("/password")
  Map<String, String> password(Actor actor, @RequestBody PasswordBody b) {
    accounts.changePassword(actor, b.current(), b.password());
    return Map.of("status", "ok");
  }

  @AllowRestricted
  @PostMapping("/totp/setup")
  TotpService.Setup totpSetup(Actor actor) {
    String issuer = settings.name().isBlank() ? "groupbase" : "groupbase · " + settings.name();
    return totp.begin(actor, issuer);
  }

  @AllowRestricted
  @PostMapping("/totp/enable")
  Map<String, String> totpEnable(Actor actor, @RequestBody CodeBody b) {
    totp.enable(actor, b.code());
    return Map.of("status", "ok");
  }

  @PostMapping("/totp/disable")
  Map<String, String> totpDisable(Actor actor, @RequestBody CodeBody b) {
    totp.disable(actor, b.code(), requireStaffTotp);
    return Map.of("status", "ok");
  }

  @DeleteMapping
  Map<String, String> delete(Actor actor, @RequestBody DeleteBody b, HttpServletResponse res) {
    accounts.deleteSelf(actor, b.password());
    cookies.clearSession(res);
    return Map.of("status", "ok");
  }
}
