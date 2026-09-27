package app.groupbase.auth;

import static app.groupbase.auth.Permission.*;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Матрица прав по умолчанию. Переопределять можно только ячейки из {@link #CONFIGURABLE} (⚙ в
 * таблице). Большинство из них по умолчанию выключены; задания и материалы у студентов — включены
 * (староста может выключить их в своей группе).
 */
public final class Rbac {

  private Rbac() {}

  /**
   * Модератор сайта следит за порядком и помогает с содержимым: новости, задания, материалы,
   * расписание. Блокировать и исключать людей могут только администратор и староста — так решил
   * владелец.
   */
  private static final Set<Permission> MODERATOR =
      EnumSet.of(
          PUBLISH_NEWS,
          PUBLISH_HOMEWORK,
          UPLOAD_MATERIALS,
          MANAGE_SCHEDULE,
          MODERATE_CONTENT,
          COMMENT,
          VIEW_AUDIT,
          VIEW_GROUP);

  private static final Map<GroupRole, Set<Permission>> GROUP_DEFAULTS =
      new EnumMap<>(GroupRole.class);

  /** Настраиваемые ячейки: роль → права, которые admin или староста могут включить и выключить. */
  public static final Map<GroupRole, Set<Permission>> CONFIGURABLE;

  static {
    GROUP_DEFAULTS.put(
        GroupRole.HEADMAN,
        EnumSet.of(
            ASSIGN_DEPUTY,
            CREATE_ACCOUNTS,
            CREATE_INVITES,
            BLOCK_USERS,
            RESET_PASSWORDS,
            MANAGE_SUBJECTS,
            SHARE_SUBJECTS,
            PUBLISH_NEWS,
            PUBLISH_HOMEWORK,
            UPLOAD_MATERIALS,
            MANAGE_SCHEDULE,
            MODERATE_CONTENT,
            COMMENT,
            VIEW_AUDIT,
            MANAGE_PERMISSIONS,
            VIEW_USERNAMES,
            EXPORT_GROUP,
            VIEW_GROUP));
    GROUP_DEFAULTS.put(
        GroupRole.DEPUTY,
        EnumSet.of(
            CREATE_INVITES,
            PUBLISH_NEWS,
            PUBLISH_HOMEWORK,
            UPLOAD_MATERIALS,
            MANAGE_SCHEDULE,
            COMMENT,
            VIEW_GROUP));
    // Задания и материалы добавляют все в группе (просьба владельца, 0.4.12): кто знает, что
    // задали, — тот и пишет. Староста может выключить это студентам своей группы (⚙).
    GROUP_DEFAULTS.put(
        GroupRole.STUDENT, EnumSet.of(PUBLISH_HOMEWORK, UPLOAD_MATERIALS, COMMENT, VIEW_GROUP));

    Map<GroupRole, Set<Permission>> cfg = new EnumMap<>(GroupRole.class);
    cfg.put(GroupRole.HEADMAN, EnumSet.noneOf(Permission.class));
    cfg.put(GroupRole.DEPUTY, EnumSet.of(CREATE_ACCOUNTS, MANAGE_SUBJECTS));
    cfg.put(
        GroupRole.STUDENT,
        EnumSet.of(PUBLISH_HOMEWORK, UPLOAD_MATERIALS, SUGGEST_MATERIALS, MANAGE_SCHEDULE));
    CONFIGURABLE = Collections.unmodifiableMap(cfg);
  }

  /** Права роли инстанса (действуют во всех группах). */
  public static boolean instanceAllows(InstanceRole role, Permission p) {
    if (role == null) {
      return false;
    }
    return switch (role) {
      case ADMIN -> true;
      case MODERATOR -> MODERATOR.contains(p);
    };
  }

  /** Значение по умолчанию для роли группы, без переопределений. */
  public static boolean groupDefault(GroupRole role, Permission p) {
    return role != null && !p.instanceLevel && GROUP_DEFAULTS.get(role).contains(p);
  }

  public static boolean isConfigurable(GroupRole role, Permission p) {
    return role != null && CONFIGURABLE.get(role).contains(p);
  }
}
