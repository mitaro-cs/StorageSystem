package app.groupbase.auth;

import app.groupbase.store.GroupStore;
import app.groupbase.store.PermissionOverrideStore;
import app.groupbase.web.ApiException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Проверка прав. Порядок: роль инстанса (admin — всё, moderator — свой набор во всех группах) →
 * роль в группе: переопределение группы → переопределение инстанса → матрица {@link Rbac}.
 * Переопределения действуют только для настраиваемых ячеек.
 */
@Service
public class Authz {

  private final GroupStore groups;
  private final PermissionOverrideStore overrides;

  /** Ключ: groupId (0 — инстанс) + роль + право. */
  private volatile Map<String, Boolean> overrideCache;

  public Authz(GroupStore groups, PermissionOverrideStore overrides) {
    this.groups = groups;
    this.overrides = overrides;
  }

  /** Может ли пользователь выполнить действие; groupId == null — уровень инстанса. */
  public boolean can(Actor actor, Permission p, Long groupId) {
    if (actor == null) {
      return false;
    }
    if (Rbac.instanceAllows(actor.instanceRole(), p)) {
      return true;
    }
    if (groupId == null || p.instanceLevel) {
      return false;
    }
    Optional<GroupRole> role = groups.role(actor.id(), groupId);
    return role.isPresent() && roleAllows(role.get(), p, groupId);
  }

  public void require(Actor actor, Permission p, Long groupId) {
    if (!can(actor, p, groupId)) {
      throw ApiException.forbidden();
    }
  }

  /** Право есть в каждой из групп (публикация сразу в несколько групп). */
  public void requireAll(Actor actor, Permission p, Collection<Long> groupIds) {
    for (Long g : groupIds) {
      require(actor, p, g);
    }
  }

  /** Право есть хотя бы в одной из групп (например, редактирование общего предмета). */
  public boolean canAny(Actor actor, Permission p, Collection<Long> groupIds) {
    for (Long g : groupIds) {
      if (can(actor, p, g)) {
        return true;
      }
    }
    return false;
  }

  /** Эффективное право роли в группе с учётом переопределений. */
  public boolean roleAllows(GroupRole role, Permission p, long groupId) {
    if (Rbac.isConfigurable(role, p)) {
      Map<String, Boolean> cache = cache();
      Boolean group = cache.get(key(groupId, role, p));
      if (group != null) {
        return group;
      }
      Boolean instance = cache.get(key(0, role, p));
      if (instance != null) {
        return instance;
      }
    }
    return Rbac.groupDefault(role, p);
  }

  /** Значение настраиваемого права на уровне инстанса (без учёта групп). */
  public boolean instanceDefault(GroupRole role, Permission p) {
    Boolean v = cache().get(key(0, role, p));
    return v != null ? v : Rbac.groupDefault(role, p);
  }

  /** Все права пользователя в группе (для интерфейса). */
  public Set<Permission> permissions(Actor actor, long groupId) {
    Set<Permission> out = EnumSet.noneOf(Permission.class);
    Optional<GroupRole> role = groups.role(actor.id(), groupId);
    for (Permission p : Permission.values()) {
      if (p.instanceLevel) {
        continue;
      }
      if (Rbac.instanceAllows(actor.instanceRole(), p)
          || (role.isPresent() && roleAllows(role.get(), p, groupId))) {
        out.add(p);
      }
    }
    return out;
  }

  public Set<Permission> instancePermissions(Actor actor) {
    Set<Permission> out = EnumSet.noneOf(Permission.class);
    for (Permission p : Permission.values()) {
      if (Rbac.instanceAllows(actor.instanceRole(), p)) {
        out.add(p);
      }
    }
    return out;
  }

  /**
   * Группы, которые пользователь видит: admin и moderator — все, остальные — свои. Используется для
   * фильтрации списков.
   */
  public List<Long> visibleGroupIds(Actor actor) {
    if (Rbac.instanceAllows(actor.instanceRole(), Permission.VIEW_GROUP)) {
      return groups.listAll().stream().map(g -> g.id()).toList();
    }
    return List.copyOf(groups.rolesOf(actor.id()).keySet());
  }

  /** Ранг пользователя в контексте группы: максимум из роли инстанса и роли в группе. */
  public int rankIn(long userId, InstanceRole instanceRole, Long groupId) {
    int rank = instanceRole == null ? 0 : instanceRole.rank;
    if (groupId != null) {
      rank = Math.max(rank, groups.role(userId, groupId).map(r -> r.rank).orElse(0));
    }
    return rank;
  }

  /** Максимальный ранг пользователя во всём инстансе (для глобальных действий: блокировка). */
  public int maxRank(long userId, InstanceRole instanceRole) {
    int rank = instanceRole == null ? 0 : instanceRole.rank;
    for (GroupRole r : groups.rolesOf(userId).values()) {
      rank = Math.max(rank, r.rank);
    }
    return rank;
  }

  public void invalidate() {
    overrideCache = null;
  }

  private Map<String, Boolean> cache() {
    Map<String, Boolean> c = overrideCache;
    if (c == null) {
      c = new ConcurrentHashMap<>();
      for (PermissionOverrideStore.Row r : overrides.all()) {
        c.put(key(r.groupId() == null ? 0 : r.groupId(), r.role(), r.permission()), r.allowed());
      }
      overrideCache = c;
    }
    return c;
  }

  private static String key(long groupId, GroupRole role, Permission p) {
    return groupId + ":" + role.name() + ":" + p.name();
  }
}
