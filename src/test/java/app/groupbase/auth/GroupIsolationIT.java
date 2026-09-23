package app.groupbase.auth;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Пользователь группы A не видит и не меняет ничего в группе B. Переопределения прав по группам.
 */
class GroupIsolationIT extends IntegrationTest {

  private static Map<String, Object> oneAccount() {
    return Map.of("accounts", List.of(Map.of("displayName", "Новый Студент")));
  }

  @Test
  void membersOfOneGroupCannotReachAnother() {
    long a = newGroup("A");
    long b = newGroup("B");
    TestUser studentA = newUser(a, "student");
    TestUser headmanA = newUser(a, "headman");
    newUser(b, "student");

    assertThat(studentA.api().get("/api/groups/" + a + "/members").status()).isEqualTo(200);
    assertThat(studentA.api().get("/api/groups/" + b + "/members").status()).isEqualTo(403);
    assertThat(studentA.api().get("/api/groups/" + b).status()).isEqualTo(403);
    assertThat(headmanA.api().post("/api/groups/" + b + "/invites", Map.of("ttlHours", 1)).status())
        .isEqualTo(403);
    assertThat(headmanA.api().post("/api/groups/" + b + "/accounts", oneAccount()).status())
        .isEqualTo(403);
    assertThat(headmanA.api().get("/api/groups/" + b + "/audit").status()).isEqualTo(403);

    JsonNode groups = studentA.api().get("/api/me").json().get("groups");
    assertThat(groups.size()).isEqualTo(1);
    assertThat(groups.get(0).get("id").asLong()).isEqualTo(a);
    assertThat(studentA.api().get("/api/groups").json().size()).isEqualTo(1);
  }

  @Test
  void studentsHaveNoManagementRights() {
    long a = newGroup("Студенты");
    TestUser s = newUser(a, "student");
    assertThat(s.api().post("/api/groups/" + a + "/invites", Map.of("ttlHours", 1)).status())
        .isEqualTo(403);
    assertThat(s.api().post("/api/groups/" + a + "/accounts", oneAccount()).status())
        .isEqualTo(403);
    assertThat(s.api().get("/api/groups/" + a + "/permissions").status()).isEqualTo(403);
    assertThat(s.api().get("/api/admin/users").status()).isEqualTo(403);
    assertThat(s.api().post("/api/groups", Map.of("name", "Своя")).status()).isEqualTo(403);
  }

  @Test
  void groupOverrideWinsOverInstanceOverride() {
    long a = newGroup("Пер-A");
    long b = newGroup("Пер-B");
    TestUser headmanA = newUser(a, "headman");
    TestUser headmanB = newUser(b, "headman");
    TestUser deputyA = newUser(a, "deputy");
    TestUser deputyB = newUser(b, "deputy");
    String path = "/permissions";
    Map<String, Object> cell = Map.of("role", "deputy", "permission", "create_accounts");

    // ⚙ по умолчанию выключено.
    assertThat(deputyA.api().post("/api/groups/" + a + "/accounts", oneAccount()).status())
        .isEqualTo(403);

    // Староста A включает право в своей группе — в группе B оно по-прежнему выключено.
    var on = headmanA.api().put("/api/groups/" + a + path, with(cell, true));
    assertThat(on.status()).isEqualTo(200);
    assertThat(deputyA.api().post("/api/groups/" + a + "/accounts", oneAccount()).status())
        .isEqualTo(200);
    assertThat(deputyB.api().post("/api/groups/" + b + "/accounts", oneAccount()).status())
        .isEqualTo(403);
    assertThat(headmanA.api().put("/api/groups/" + b + path, with(cell, true)).status())
        .isEqualTo(403);

    // Admin включает на уровне инстанса — теперь и в B.
    try {
      assertThat(admin().put("/api/admin/permissions", with(cell, true)).status()).isEqualTo(200);
      assertThat(deputyB.api().post("/api/groups/" + b + "/accounts", oneAccount()).status())
          .isEqualTo(200);
      // Староста B выключает у себя — переопределение группы важнее.
      headmanB.api().put("/api/groups/" + b + path, with(cell, false));
      assertThat(deputyB.api().post("/api/groups/" + b + "/accounts", oneAccount()).status())
          .isEqualTo(403);
      // Сброс переопределения группы возвращает значение инстанса.
      headmanB.api().put("/api/groups/" + b + path, with(cell, null));
      assertThat(deputyB.api().post("/api/groups/" + b + "/accounts", oneAccount()).status())
          .isEqualTo(200);
    } finally {
      admin().put("/api/admin/permissions", with(cell, null));
    }
  }

  @Test
  void onlyConfigurableCellsCanBeChanged() {
    long a = newGroup("Настройки");
    TestUser headman = newUser(a, "headman");
    var r =
        headman
            .api()
            .put(
                "/api/groups/" + a + "/permissions",
                Map.of("role", "student", "permission", "moderate_content", "allowed", true));
    assertThat(r.status()).isEqualTo(400);
    var list = headman.api().get("/api/groups/" + a + "/permissions").json();
    assertThat(list.size()).isEqualTo(3);
  }

  @Test
  void roleChangesFollowHierarchy() {
    long a = newGroup("Иерархия");
    TestUser headman = newUser(a, "headman");
    TestUser deputy = newUser(a, "deputy");
    TestUser student = newUser(a, "student");
    String roleOf = "/api/groups/" + a + "/members/";

    assertThat(
            headman.api().put(roleOf + student.id() + "/role", Map.of("role", "deputy")).status())
        .isEqualTo(200);
    assertThat(
            headman.api().put(roleOf + student.id() + "/role", Map.of("role", "headman")).status())
        .isEqualTo(403);
    assertThat(
            deputy.api().put(roleOf + student.id() + "/role", Map.of("role", "student")).status())
        .isEqualTo(403);
    assertThat(
            headman.api().put(roleOf + headman.id() + "/role", Map.of("role", "student")).status())
        .isEqualTo(403);
    assertThat(admin().put(roleOf + student.id() + "/role", Map.of("role", "headman")).status())
        .isEqualTo(200);
  }

  @Test
  void blockingRespectsRanksAcrossGroups() {
    long a = newGroup("Ранг-A");
    long b = newGroup("Ранг-B");
    TestUser headmanA = newUser(a, "headman");
    TestUser headmanB = newUser(b, "headman");
    // Староста B ещё и студент в группе A — староста A не может его заблокировать.
    admin().post("/api/groups/" + a + "/invites", Map.of("ttlHours", 1)).json();
    var inv = admin().post("/api/groups/" + a + "/invites", Map.of("ttlHours", 1)).json();
    String t = inv.get("path").asString().substring("/invite/".length());
    headmanB.api().post("/api/invites/" + t + "/join", null);
    assertThat(
            headmanA
                .api()
                .post("/api/groups/" + a + "/members/" + headmanB.id() + "/block", null)
                .status())
        .isEqualTo(403);

    TestUser student = newUser(a, "student");
    assertThat(
            headmanA
                .api()
                .post("/api/groups/" + a + "/members/" + student.id() + "/block", null)
                .status())
        .isEqualTo(200);
    // Участника чужой группы через свою группу заблокировать нельзя.
    TestUser studentB = newUser(b, "student");
    assertThat(
            headmanA
                .api()
                .post("/api/groups/" + a + "/members/" + studentB.id() + "/block", null)
                .status())
        .isEqualTo(404);
  }

  @Test
  void moderatorActsInstanceWideButNotOnAdmins() {
    long a = newGroup("Модер");
    TestUser mod = newUser(a, "student");
    assertThat(
            admin()
                .put("/api/admin/users/" + mod.id() + "/instance-role", Map.of("role", "moderator"))
                .status())
        .isEqualTo(200);
    var modApi = login(mod.username(), PASSWORD);
    long b = newGroup("Чужая");
    TestUser victim = newUser(b, "student");
    assertThat(modApi.get("/api/groups/" + b + "/members").status()).isEqualTo(200);
    assertThat(modApi.post("/api/admin/users/" + victim.id() + "/block", null).status())
        .isEqualTo(200);
    long adminId = admin().get("/api/me").json().get("user").get("id").asLong();
    assertThat(modApi.post("/api/admin/users/" + adminId + "/block", null).status()).isEqualTo(403);
    assertThat(modApi.patch("/api/admin/settings", Map.of("name", "x")).status()).isEqualTo(403);
    assertThat(modApi.post("/api/groups/" + b + "/invites", Map.of("ttlHours", 1)).status())
        .isEqualTo(403);
  }

  private static Map<String, Object> with(Map<String, Object> cell, Boolean allowed) {
    var m = new java.util.HashMap<>(cell);
    m.put("allowed", allowed);
    return m;
  }
}
