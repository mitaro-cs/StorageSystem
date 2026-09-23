package app.groupbase.auth;

import static app.groupbase.auth.Permission.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Каждая ячейка таблицы прав из требований. ⚙-ячейки по умолчанию выключены и должны быть
 * настраиваемыми; остальные — не настраиваемыми.
 */
class RbacMatrixTest {

  // Столбцы: admin, moderator, headman, deputy, student. 'y' — да, '-' — нет, 'c' — ⚙ (выкл.).
  private static final Object[][] TABLE = {
    {MANAGE_INSTANCE, "y----"},
    {ASSIGN_MODERATOR, "y----"},
    {ASSIGN_HEADMAN, "y----"},
    {ASSIGN_DEPUTY, "y-y--"},
    {CREATE_ACCOUNTS, "y-yc-"},
    {CREATE_INVITES, "y-yy-"},
    {BLOCK_USERS, "yyy--"},
    {RESET_PASSWORDS, "y-y--"},
    {MANAGE_SUBJECTS, "y-yc-"},
    {SHARE_SUBJECTS, "y-y--"},
    {PUBLISH_NEWS, "yyyy-"},
    {PUBLISH_HOMEWORK, "y-yy-"},
    {UPLOAD_MATERIALS, "y-yy-"},
    {SUGGEST_MATERIALS, "y---c"},
    {MODERATE_CONTENT, "yyy--"},
    {COMMENT, "yyyyy"},
    {VIEW_AUDIT, "yyy--"},
    {MANAGE_PERMISSIONS, "y-y--"},
    {VIEW_USERNAMES, "y-y--"},
    {VIEW_GROUP, "yyyyy"},
  };

  static Stream<Arguments> cells() {
    List<Arguments> out = new ArrayList<>();
    for (Object[] row : TABLE) {
      Permission p = (Permission) row[0];
      String s = (String) row[1];
      out.add(Arguments.of(p, "admin", s.charAt(0)));
      out.add(Arguments.of(p, "moderator", s.charAt(1)));
      out.add(Arguments.of(p, "headman", s.charAt(2)));
      out.add(Arguments.of(p, "deputy", s.charAt(3)));
      out.add(Arguments.of(p, "student", s.charAt(4)));
    }
    return out.stream();
  }

  @ParameterizedTest(name = "{0} / {1} = {2}")
  @MethodSource("cells")
  void cell(Permission p, String role, char expected) {
    boolean actual;
    boolean configurable;
    switch (role) {
      case "admin" -> {
        actual = Rbac.instanceAllows(InstanceRole.ADMIN, p);
        configurable = false;
      }
      case "moderator" -> {
        actual = Rbac.instanceAllows(InstanceRole.MODERATOR, p);
        configurable = false;
      }
      default -> {
        GroupRole r = GroupRole.of(role);
        actual = Rbac.groupDefault(r, p);
        configurable = Rbac.isConfigurable(r, p);
      }
    }
    assertThat(actual).as("значение по умолчанию").isEqualTo(expected == 'y');
    assertThat(configurable).as("настраиваемость").isEqualTo(expected == 'c');
  }

  @Test
  void tableCoversEveryPermission() {
    Set<Permission> covered = EnumSet.noneOf(Permission.class);
    for (Object[] row : TABLE) {
      covered.add((Permission) row[0]);
    }
    assertThat(covered).containsExactlyInAnyOrder(Permission.values());
  }

  @Test
  void instanceLevelPermissionsNeverComeFromGroupRoles() {
    for (GroupRole r : GroupRole.values()) {
      assertThat(Rbac.groupDefault(r, MANAGE_INSTANCE)).isFalse();
      assertThat(Rbac.groupDefault(r, ASSIGN_MODERATOR)).isFalse();
    }
  }
}
