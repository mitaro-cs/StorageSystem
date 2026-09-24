package app.groupbase.web.api;

import app.groupbase.accounts.AccountService;
import app.groupbase.accounts.InstanceSettings;
import app.groupbase.accounts.PublicUrl;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Authz;
import app.groupbase.auth.GroupRole;
import app.groupbase.auth.InstanceRole;
import app.groupbase.auth.Permission;
import app.groupbase.auth.TotpService;
import app.groupbase.cli.Main;
import app.groupbase.config.GroupbaseProperties;
import app.groupbase.store.Group;
import app.groupbase.store.GroupChatStore;
import app.groupbase.store.GroupStore;
import app.groupbase.store.User;
import app.groupbase.store.UserStore;
import app.groupbase.web.AllowRestricted;
import app.groupbase.web.ApiException;
import app.groupbase.web.Cookies;
import jakarta.servlet.http.HttpServletRequest;
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
      boolean totpEnabled,
      boolean manageMode) {}

  record GroupView(
      long id,
      String slug,
      String name,
      String university,
      Integer course,
      String avatar,
      boolean archived,
      GroupRole role,
      Set<Permission> permissions,
      List<GroupChatController.ChatView> chats) {}

  /**
   * @param publicUrl адрес сайта для участников (для ссылок и QR), null — адрес из браузера
   * @param desktop сервер работает в приложении хоста на его компьютере
   */
  record InstanceView(
      String name,
      String mode,
      String version,
      boolean requireStaffTotp,
      String publicUrl,
      boolean desktop) {}

  record MeView(
      UserView user,
      String restriction,
      InstanceView instance,
      List<GroupView> groups,
      Set<Permission> permissions,
      boolean hostWindow) {}

  record ProfileBody(String displayName) {}

  record PreferencesBody(Boolean manageMode) {}

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
  private final boolean desktop;
  private final PublicUrl publicUrl;
  private final GroupChatStore chats;

  MeController(
      UserStore users,
      GroupStore groups,
      Authz authz,
      InstanceSettings settings,
      AccountService accounts,
      TotpService totp,
      Cookies cookies,
      GroupbaseProperties props,
      PublicUrl publicUrl,
      GroupChatStore chats) {
    this.users = users;
    this.groups = groups;
    this.authz = authz;
    this.settings = settings;
    this.accounts = accounts;
    this.totp = totp;
    this.cookies = cookies;
    this.requireStaffTotp = props.auth().requireStaffTotp();
    this.desktop = props.desktop().enabled();
    this.publicUrl = publicUrl;
    this.chats = chats;
  }

  @AllowRestricted
  @GetMapping
  MeView me(Actor actor) {
    User u = users.find(actor.id()).orElseThrow(ApiException::unauthorized);
    Map<Long, GroupRole> roles = groups.rolesOf(actor.id());
    List<Group> visible =
        actor.restriction() != null ? List.of() : groups.listByIds(authz.visibleGroupIds(actor));
    Map<Long, List<GroupChatStore.Chat>> pinned =
        chats.byGroup(visible.stream().map(Group::id).toList());
    List<GroupView> gs =
        visible.stream()
            .map(
                g ->
                    view(
                        g,
                        roles.get(g.id()),
                        actor,
                        GroupChatController.views(pinned.getOrDefault(g.id(), List.of()))))
            .toList();
    String name = settings.name();
    return new MeView(
        new UserView(
            u.id(),
            u.username(),
            u.displayName(),
            u.avatar(),
            u.instanceRole(),
            u.totpEnabled(),
            users.manageMode(u.id())),
        actor.restriction() == null ? null : actor.restriction().id(),
        new InstanceView(
            name,
            settings.mode().id(),
            Main.version(),
            requireStaffTotp,
            publicUrl.get().orElse(null),
            desktop),
        gs,
        authz.instancePermissions(actor),
        actor.local());
  }

  /** Режим управления: выключенный прячет кнопки администратора и старосты в интерфейсе. */
  @PatchMapping("/preferences")
  Map<String, Object> preferences(Actor actor, @RequestBody PreferencesBody b) {
    if (b.manageMode() != null) {
      users.setManageMode(actor.id(), b.manageMode());
    }
    return Map.of("manageMode", users.manageMode(actor.id()));
  }

  private GroupView view(
      Group g, GroupRole role, Actor actor, List<GroupChatController.ChatView> chats) {
    return new GroupView(
        g.id(),
        g.slug(),
        g.name(),
        g.university(),
        g.course(),
        g.avatar(),
        g.archivedAt() != null,
        role,
        authz.permissions(actor, g.id()),
        chats);
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
  Map<String, Object> totpEnable(Actor actor, @RequestBody CodeBody b) {
    return Map.of("status", "ok", "recoveryCodes", totp.enable(actor, b.code()));
  }

  @GetMapping("/totp/recovery")
  Map<String, Integer> recoveryLeft(Actor actor) {
    return Map.of("remaining", totp.recoveryLeft(actor));
  }

  @PostMapping("/totp/recovery")
  Map<String, Object> recoveryRegenerate(Actor actor, @RequestBody CodeBody b) {
    return Map.of("recoveryCodes", totp.regenerateRecovery(actor, b.code()));
  }

  @PostMapping("/totp/disable")
  Map<String, String> totpDisable(Actor actor, @RequestBody CodeBody b) {
    totp.disable(actor, b.code(), requireStaffTotp);
    return Map.of("status", "ok");
  }

  @DeleteMapping
  Map<String, String> delete(
      Actor actor, @RequestBody DeleteBody b, HttpServletRequest req, HttpServletResponse res) {
    accounts.deleteSelf(actor, b.password());
    cookies.clearSession(req, res);
    return Map.of("status", "ok");
  }
}
