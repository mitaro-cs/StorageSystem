package app.groupbase.web.api;

import app.groupbase.accounts.AccountService;
import app.groupbase.accounts.GroupService;
import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Authz;
import app.groupbase.auth.GroupRole;
import app.groupbase.auth.Permission;
import app.groupbase.auth.Rbac;
import app.groupbase.store.AuditStore;
import app.groupbase.store.Group;
import app.groupbase.store.GroupStore;
import app.groupbase.store.Member;
import app.groupbase.store.PermissionOverrideStore;
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

@RestController
@RequestMapping("/api/groups")
class GroupController {

  record RoleBody(GroupRole role) {}

  record ArchiveBody(Boolean archived) {}

  record AccountsBody(
      List<AccountService.NewAccount> accounts, GroupRole role, AccountService.Delivery delivery) {}

  record PermissionCell(
      GroupRole role, Permission permission, boolean allowed, boolean overridden) {}

  record PermissionBody(GroupRole role, Permission permission, Boolean allowed) {}

  private final GroupStore groups;
  private final GroupService groupService;
  private final AccountService accounts;
  private final Authz authz;
  private final PermissionOverrideStore overrides;
  private final AuditService audit;
  private final Clock clock;

  GroupController(
      GroupStore groups,
      GroupService groupService,
      AccountService accounts,
      Authz authz,
      PermissionOverrideStore overrides,
      AuditService audit,
      Clock clock) {
    this.groups = groups;
    this.groupService = groupService;
    this.accounts = accounts;
    this.authz = authz;
    this.overrides = overrides;
    this.audit = audit;
    this.clock = clock;
  }

  @GetMapping
  List<Group> list(Actor actor) {
    return groupService.visible(actor);
  }

  record DirectoryEntry(long id, String name, String university) {}

  /** Все активные группы инстанса: чтобы выбрать, с кем поделиться предметом. */
  @GetMapping("/directory")
  List<DirectoryEntry> directory() {
    return groups.listAll().stream()
        .filter(g -> g.archivedAt() == null)
        .map(g -> new DirectoryEntry(g.id(), g.name(), g.university()))
        .toList();
  }

  @Require(Permission.MANAGE_INSTANCE)
  @PostMapping
  Group create(Actor actor, @RequestBody GroupService.GroupInput in) {
    return groupService.create(actor, in);
  }

  @GetMapping("/{groupId}")
  Group get(@PathVariable long groupId) {
    return groups.find(groupId).orElseThrow(ApiException::notFound);
  }

  @Require(Permission.MANAGE_INSTANCE)
  @PatchMapping("/{groupId}")
  Group update(Actor actor, @PathVariable long groupId, @RequestBody GroupService.GroupInput in) {
    return groupService.update(actor, groupId, in);
  }

  @Require(Permission.MANAGE_INSTANCE)
  @PostMapping("/{groupId}/archive")
  Map<String, String> archive(Actor actor, @PathVariable long groupId, @RequestBody ArchiveBody b) {
    groupService.setArchived(actor, groupId, Boolean.TRUE.equals(b.archived()));
    return Map.of("status", "ok");
  }

  // --- участники ---

  @GetMapping("/{groupId}/members")
  List<Member> members(@PathVariable long groupId) {
    return groups.members(groupId);
  }

  @PutMapping("/{groupId}/members/{userId}/role")
  Map<String, String> setRole(
      Actor actor, @PathVariable long groupId, @PathVariable long userId, @RequestBody RoleBody b) {
    if (b.role() == null) {
      throw ApiException.invalid("role", "Укажите роль");
    }
    accounts.setGroupRole(actor, groupId, userId, b.role());
    return Map.of("status", "ok");
  }

  @DeleteMapping("/{groupId}/members/{userId}")
  Map<String, String> remove(Actor actor, @PathVariable long groupId, @PathVariable long userId) {
    accounts.removeFromGroup(actor, groupId, userId);
    return Map.of("status", "ok");
  }

  @Require(Permission.CREATE_ACCOUNTS)
  @PostMapping("/{groupId}/accounts")
  List<AccountService.Created> createAccounts(
      Actor actor, @PathVariable long groupId, @RequestBody AccountsBody b) {
    return accounts.create(
        actor,
        groupId,
        b.accounts() == null ? List.of() : b.accounts(),
        b.role() == null ? GroupRole.STUDENT : b.role(),
        b.delivery() == null ? AccountService.Delivery.LINK : b.delivery());
  }

  @Require(Permission.RESET_PASSWORDS)
  @PostMapping("/{groupId}/members/{userId}/reset-link")
  Map<String, String> resetLink(
      Actor actor, @PathVariable long groupId, @PathVariable long userId) {
    return Map.of("path", accounts.resetLink(actor, userId, groupId));
  }

  @Require(Permission.BLOCK_USERS)
  @PostMapping("/{groupId}/members/{userId}/block")
  Map<String, String> block(Actor actor, @PathVariable long groupId, @PathVariable long userId) {
    accounts.setBlocked(actor, userId, groupId, true);
    return Map.of("status", "ok");
  }

  @Require(Permission.BLOCK_USERS)
  @PostMapping("/{groupId}/members/{userId}/unblock")
  Map<String, String> unblock(Actor actor, @PathVariable long groupId, @PathVariable long userId) {
    accounts.setBlocked(actor, userId, groupId, false);
    return Map.of("status", "ok");
  }

  @Require(Permission.BLOCK_USERS)
  @DeleteMapping("/{groupId}/members/{userId}/account")
  Map<String, String> deleteAccount(
      Actor actor, @PathVariable long groupId, @PathVariable long userId) {
    accounts.deleteUser(actor, userId, groupId);
    return Map.of("status", "ok");
  }

  // --- настраиваемые права (⚙) ---

  @Require(Permission.MANAGE_PERMISSIONS)
  @GetMapping("/{groupId}/permissions")
  List<PermissionCell> permissions(@PathVariable long groupId) {
    List<PermissionCell> out = new ArrayList<>();
    var rows = overrides.all();
    for (var e : Rbac.CONFIGURABLE.entrySet()) {
      for (Permission p : e.getValue()) {
        boolean overridden =
            rows.stream()
                .anyMatch(
                    r ->
                        r.groupId() != null
                            && r.groupId() == groupId
                            && r.role() == e.getKey()
                            && r.permission() == p);
        out.add(
            new PermissionCell(
                e.getKey(), p, authz.roleAllows(e.getKey(), p, groupId), overridden));
      }
    }
    return out;
  }

  /** allowed == null — сбросить к значению инстанса. */
  @Require(Permission.MANAGE_PERMISSIONS)
  @PutMapping("/{groupId}/permissions")
  List<PermissionCell> setPermission(
      Actor actor, @PathVariable long groupId, @RequestBody PermissionBody b) {
    if (b.role() == null
        || b.permission() == null
        || !Rbac.isConfigurable(b.role(), b.permission())) {
      throw ApiException.badRequest("Это право нельзя настроить");
    }
    if (b.allowed() == null) {
      overrides.delete(groupId, b.role(), b.permission());
    } else {
      overrides.upsert(groupId, b.role(), b.permission(), b.allowed(), actor.id(), clock.millis());
    }
    authz.invalidate();
    audit.log(
        actor,
        groupId,
        "permissions.update",
        "group",
        groupId,
        Map.of(
            "role", b.role().id(),
            "permission", b.permission().id(),
            "allowed", String.valueOf(b.allowed())));
    return permissions(groupId);
  }

  @Require(Permission.VIEW_AUDIT)
  @GetMapping("/{groupId}/audit")
  List<AuditStore.Entry> audit(
      @PathVariable long groupId, @RequestParam(required = false) Long before) {
    return audit.list(List.of(groupId), before, 50).stream()
        .map(
            e ->
                new AuditStore.Entry(
                    e.id(),
                    e.at(),
                    e.actorId(),
                    e.actorName(),
                    e.groupId(),
                    e.action(),
                    e.targetType(),
                    e.targetId(),
                    e.details(),
                    null))
        .toList();
  }
}
