package app.groupbase.web.api;

import app.groupbase.accounts.AccountService;
import app.groupbase.accounts.InstanceSettings;
import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Authz;
import app.groupbase.auth.GroupRole;
import app.groupbase.auth.InstanceRole;
import app.groupbase.auth.Permission;
import app.groupbase.auth.Rbac;
import app.groupbase.store.AuditStore;
import app.groupbase.store.GroupStore;
import app.groupbase.store.PermissionOverrideStore;
import app.groupbase.store.UserStore;
import app.groupbase.web.ApiException;
import app.groupbase.web.Require;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Уровень инстанса: настройки, все пользователи, роли admin/moderator, журнал. */
@RestController
@RequestMapping("/api/admin")
class AdminController {

  record SettingsView(String name, String mode, boolean directAccounts, boolean invites) {}

  record SettingsBody(String name, String mode, Boolean directAccounts, Boolean invites) {}

  record UserRow(
      long id,
      String username,
      String displayName,
      String avatar,
      InstanceRole instanceRole,
      String status,
      boolean totpEnabled,
      Map<Long, GroupRole> groups,
      long createdAt) {}

  record InstanceRoleBody(InstanceRole role) {}

  record AccountsBody(List<AccountService.NewAccount> accounts, AccountService.Delivery delivery) {}

  record PermissionCell(GroupRole role, Permission permission, boolean allowed) {}

  record PermissionBody(GroupRole role, Permission permission, Boolean allowed) {}

  private final InstanceSettings settings;
  private final UserStore users;
  private final GroupStore groups;
  private final AccountService accounts;
  private final Authz authz;
  private final PermissionOverrideStore overrides;
  private final AuditService audit;
  private final Clock clock;

  AdminController(
      InstanceSettings settings,
      UserStore users,
      GroupStore groups,
      AccountService accounts,
      Authz authz,
      PermissionOverrideStore overrides,
      AuditService audit,
      Clock clock) {
    this.settings = settings;
    this.users = users;
    this.groups = groups;
    this.accounts = accounts;
    this.authz = authz;
    this.overrides = overrides;
    this.audit = audit;
    this.clock = clock;
  }

  @Require(Permission.MANAGE_INSTANCE)
  @GetMapping("/settings")
  SettingsView settings() {
    return new SettingsView(
        settings.name(), settings.mode().id(), settings.directAccounts(), settings.invites());
  }

  @Require(Permission.MANAGE_INSTANCE)
  @PatchMapping("/settings")
  SettingsView update(Actor actor, @RequestBody SettingsBody b) {
    if (b.name() != null) {
      String n = b.name().strip();
      if (n.length() > 60) {
        throw ApiException.invalid("name", "Название — до 60 символов");
      }
      settings.set(InstanceSettings.NAME, n);
    }
    if (b.mode() != null) {
      InstanceSettings.Mode mode;
      try {
        mode = InstanceSettings.Mode.of(b.mode());
      } catch (IllegalArgumentException e) {
        throw ApiException.invalid("mode", "Режим: single или multi");
      }
      if (mode == InstanceSettings.Mode.SINGLE && groups.count() > 1) {
        throw ApiException.conflict(
            "many_groups", "В инстансе несколько групп: режим одной группы недоступен");
      }
      settings.set(InstanceSettings.MODE, mode.id());
    }
    if (b.directAccounts() != null) {
      settings.set(InstanceSettings.ACCOUNTS_DIRECT, b.directAccounts().toString());
    }
    if (b.invites() != null) {
      settings.set(InstanceSettings.ACCOUNTS_INVITES, b.invites().toString());
    }
    audit.log(actor, null, "settings.update", "instance", null);
    return settings();
  }

  @Require(Permission.BLOCK_USERS)
  @GetMapping("/users")
  List<UserRow> users() {
    return users.listAll().stream()
        .map(
            u ->
                new UserRow(
                    u.id(),
                    u.username(),
                    u.displayName(),
                    u.avatar(),
                    u.instanceRole(),
                    u.status().id(),
                    u.totpEnabled(),
                    groups.rolesOf(u.id()),
                    u.createdAt()))
        .toList();
  }

  @Require(Permission.CREATE_ACCOUNTS)
  @PostMapping("/users")
  List<AccountService.Created> create(Actor actor, @RequestBody AccountsBody b) {
    return accounts.create(
        actor,
        null,
        b.accounts() == null ? List.of() : b.accounts(),
        null,
        b.delivery() == null ? AccountService.Delivery.LINK : b.delivery());
  }

  @Require(Permission.ASSIGN_MODERATOR)
  @PutMapping("/users/{userId}/instance-role")
  Map<String, String> instanceRole(
      Actor actor, @PathVariable long userId, @RequestBody InstanceRoleBody b) {
    accounts.setInstanceRole(actor, userId, b.role());
    return Map.of("status", "ok");
  }

  @Require(Permission.BLOCK_USERS)
  @PostMapping("/users/{userId}/block")
  Map<String, String> block(Actor actor, @PathVariable long userId) {
    accounts.setBlocked(actor, userId, null, true);
    return Map.of("status", "ok");
  }

  @Require(Permission.BLOCK_USERS)
  @PostMapping("/users/{userId}/unblock")
  Map<String, String> unblock(Actor actor, @PathVariable long userId) {
    accounts.setBlocked(actor, userId, null, false);
    return Map.of("status", "ok");
  }

  @Require(Permission.BLOCK_USERS)
  @DeleteMapping("/users/{userId}")
  Map<String, String> delete(Actor actor, @PathVariable long userId) {
    accounts.deleteUser(actor, userId, null);
    return Map.of("status", "ok");
  }

  @Require(Permission.RESET_PASSWORDS)
  @PostMapping("/users/{userId}/reset-link")
  Map<String, String> resetLink(Actor actor, @PathVariable long userId) {
    return Map.of("path", accounts.resetLink(actor, userId, null));
  }

  @Require(Permission.MANAGE_INSTANCE)
  @GetMapping("/permissions")
  List<PermissionCell> permissions() {
    List<PermissionCell> out = new ArrayList<>();
    for (var e : Rbac.CONFIGURABLE.entrySet()) {
      for (Permission p : e.getValue()) {
        out.add(new PermissionCell(e.getKey(), p, authz.instanceDefault(e.getKey(), p)));
      }
    }
    return out;
  }

  @Require(Permission.MANAGE_INSTANCE)
  @PutMapping("/permissions")
  List<PermissionCell> setPermission(Actor actor, @RequestBody PermissionBody b) {
    if (b.role() == null
        || b.permission() == null
        || !Rbac.isConfigurable(b.role(), b.permission())) {
      throw ApiException.badRequest("Это право нельзя настроить");
    }
    if (b.allowed() == null) {
      overrides.delete(null, b.role(), b.permission());
    } else {
      overrides.upsert(null, b.role(), b.permission(), b.allowed(), actor.id(), clock.millis());
    }
    authz.invalidate();
    audit.log(
        actor,
        null,
        "permissions.update",
        "instance",
        null,
        Map.of("role", b.role().id(), "permission", b.permission().id()));
    return permissions();
  }

  @Require(Permission.VIEW_AUDIT)
  @GetMapping("/audit")
  List<AuditStore.Entry> audit(@RequestParam(required = false) Long before) {
    return audit.list(null, before, 50);
  }
}
