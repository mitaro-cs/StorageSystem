package app.groupbase.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.ApiClient;
import app.groupbase.IntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AccountsIT extends IntegrationTest {

  private static String token(String path) {
    return path.substring(path.lastIndexOf('/') + 1);
  }

  @Test
  void activationLinkIsOneTime() {
    long g = newGroup("Активация");
    var r =
        admin()
            .post(
                "/api/groups/" + g + "/accounts",
                Map.of("accounts", List.of(Map.of("displayName", "Иванова Мария"))));
    assertThat(r.status()).isEqualTo(200);
    JsonNode created = r.json().get(0);
    assertThat(created.get("username").asString()).startsWith("ivanova.mariya");
    String t = token(created.get("activationPath").asString());

    var info = client().get("/api/auth/links/" + t);
    assertThat(info.json().get("purpose").asString()).isEqualTo("activate");
    assertThat(info.json().get("displayName").asString()).isEqualTo("Иванова Мария");

    // До активации войти нельзя.
    var early =
        client()
            .post(
                "/api/auth/login",
                Map.of("username", created.get("username").asString(), "password", PASSWORD));
    assertThat(early.status()).isEqualTo(401);

    assertThat(client().post("/api/auth/links/" + t, Map.of("password", "короткий")).status())
        .isEqualTo(400);
    ApiClient c = client();
    assertThat(c.post("/api/auth/links/" + t, Map.of("password", PASSWORD)).status())
        .isEqualTo(200);
    assertThat(c.get("/api/me").json().get("groups").get(0).get("role").asString())
        .isEqualTo("student");
    assertThat(client().post("/api/auth/links/" + t, Map.of("password", PASSWORD)).status())
        .isEqualTo(410);
  }

  @Test
  void temporaryPasswordForcesChange() {
    long g = newGroup("Временный");
    var r =
        admin()
            .post(
                "/api/groups/" + g + "/accounts",
                Map.of(
                    "accounts",
                    List.of(Map.of("displayName", "Сидоров Пётр")),
                    "delivery",
                    "PASSWORD"));
    JsonNode created = r.json().get(0);
    String username = created.get("username").asString();
    ApiClient c = login(username, created.get("temporaryPassword").asString());

    var me = c.get("/api/me");
    assertThat(me.json().get("restriction").asString()).isEqualTo("password_change_required");
    var blocked = c.get("/api/groups");
    assertThat(blocked.status()).isEqualTo(403);
    assertThat(blocked.error()).isEqualTo("password_change_required");

    assertThat(c.post("/api/me/password", Map.of("password", PASSWORD)).status()).isEqualTo(200);
    assertThat(c.get("/api/me").json().get("restriction").isNull()).isTrue();
    assertThat(c.get("/api/groups").status()).isEqualTo(200);
  }

  @Test
  void bulkCreationGeneratesUniqueUsernames() {
    long g = newGroup("Список");
    var r =
        admin()
            .post(
                "/api/groups/" + g + "/accounts",
                Map.of(
                    "accounts",
                    List.of(
                        Map.of("displayName", "Смирнова Анна"),
                        Map.of("displayName", "Смирнова Анна"),
                        Map.of("displayName", "Ким Олег"))));
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.json().size()).isEqualTo(3);
    assertThat(r.json().get(0).get("username").asString())
        .isNotEqualTo(r.json().get(1).get("username").asString());
    assertThat(admin().get("/api/groups/" + g + "/members").json().size()).isEqualTo(3);
  }

  @Test
  void duplicateUsernameRollsBackWholeBatch() {
    long g = newGroup("Дубли");
    TestUser existing = newUser(g, "student");
    var r =
        admin()
            .post(
                "/api/groups/" + g + "/accounts",
                Map.of(
                    "accounts",
                    List.of(
                        Map.of("username", "fresh" + uniq(), "displayName", "Новиков Новый"),
                        Map.of("username", existing.username(), "displayName", "Дублев Дубль"))));
    assertThat(r.status()).isEqualTo(409);
    assertThat(r.json().get("details").get("row").asInt()).isEqualTo(1);
    assertThat(admin().get("/api/groups/" + g + "/members").json().size()).isEqualTo(1);
  }

  @Test
  void invitesRespectUsesTtlAndRevocation() {
    long g = newGroup("Инвайты");
    ApiClient a = admin();
    var single = a.post("/api/groups/" + g + "/invites", Map.of("maxUses", 1, "ttlHours", 24));
    assertThat(single.status()).isEqualTo(200);
    String t = token(single.json().get("path").asString());

    var info = client().get("/api/invites/" + t);
    assertThat(info.json().get("valid").asBoolean()).isTrue();
    assertThat(info.json().get("role").asString()).isEqualTo("student");

    ApiClient newbie = client();
    var accept =
        newbie.post(
            "/api/invites/" + t + "/accept",
            Map.of(
                "username", "inv" + uniq(), "displayName", "Новенький Иван", "password", PASSWORD));
    assertThat(accept.status()).as(accept.body()).isEqualTo(200);
    assertThat(newbie.get("/api/me").json().get("groups").size()).isEqualTo(1);

    var second =
        client()
            .post(
                "/api/invites/" + t + "/accept",
                Map.of(
                    "username",
                    "inv" + uniq(),
                    "displayName",
                    "Вторых Пётр",
                    "password",
                    PASSWORD));
    assertThat(second.status()).isEqualTo(410);

    var multi = a.post("/api/groups/" + g + "/invites", Map.of("ttlHours", 1));
    String m = token(multi.json().get("path").asString());
    clock.advance(Duration.ofHours(2));
    assertThat(client().get("/api/invites/" + m).json().get("valid").asBoolean()).isFalse();

    var revoked = a.post("/api/groups/" + g + "/invites", Map.of("ttlHours", 5));
    long id = revoked.json().get("invite").get("id").asLong();
    assertThat(a.delete("/api/groups/" + g + "/invites/" + id).status()).isEqualTo(200);
    String rt = token(revoked.json().get("path").asString());
    assertThat(
            client()
                .post(
                    "/api/invites/" + rt + "/accept",
                    Map.of(
                        "username",
                        "inv" + uniq(),
                        "displayName",
                        "Иксов Икс",
                        "password",
                        PASSWORD))
                .status())
        .isEqualTo(410);
  }

  @Test
  void existingUserJoinsAnotherGroupByInvite() {
    long g1 = newGroup("Первая");
    long g2 = newGroup("Вторая");
    TestUser u = newUser(g1, "student");
    var inv = admin().post("/api/groups/" + g2 + "/invites", Map.of("ttlHours", 24));
    String t = token(inv.json().get("path").asString());
    assertThat(u.api().post("/api/invites/" + t + "/join", null).status()).isEqualTo(200);
    assertThat(u.api().get("/api/me").json().get("groups").size()).isEqualTo(2);
    assertThat(u.api().post("/api/invites/" + t + "/join", null).status()).isEqualTo(409);
  }

  @Test
  void rolesInInvitesAreLimitedByActor() {
    long g = newGroup("Роли");
    TestUser headman = newUser(g, "headman");
    TestUser deputy = newUser(g, "deputy");
    assertThat(
            headman
                .api()
                .post("/api/groups/" + g + "/invites", Map.of("role", "deputy", "ttlHours", 1))
                .status())
        .isEqualTo(200);
    assertThat(
            headman
                .api()
                .post("/api/groups/" + g + "/invites", Map.of("role", "headman", "ttlHours", 1))
                .status())
        .isEqualTo(403);
    assertThat(
            deputy
                .api()
                .post("/api/groups/" + g + "/invites", Map.of("role", "student", "ttlHours", 1))
                .status())
        .isEqualTo(200);
    assertThat(
            deputy
                .api()
                .post("/api/groups/" + g + "/invites", Map.of("role", "deputy", "ttlHours", 1))
                .status())
        .isEqualTo(403);
  }

  @Test
  void headmanResetsPasswordOfOwnStudent() {
    long g = newGroup("Сброс");
    TestUser headman = newUser(g, "headman");
    TestUser student = newUser(g, "student");
    var r =
        headman.api().post("/api/groups/" + g + "/members/" + student.id() + "/reset-link", null);
    assertThat(r.status()).isEqualTo(200);
    String t = token(r.json().get("path").asString());
    assertThat(client().get("/api/auth/links/" + t).json().get("purpose").asString())
        .isEqualTo("reset");
    assertThat(
            client().post("/api/auth/links/" + t, Map.of("password", "brand-new-pass-7")).status())
        .isEqualTo(200);
    // Старая сессия студента после сброса пароля закрыта.
    assertThat(student.api().get("/api/me").status()).isEqualTo(401);
    login(student.username(), "brand-new-pass-7");

    // Сбросить пароль другому старосте или себе нельзя.
    TestUser other = newUser(g, "headman");
    assertThat(
            headman
                .api()
                .post("/api/groups/" + g + "/members/" + other.id() + "/reset-link", null)
                .status())
        .isEqualTo(403);
  }

  @Test
  void deletingAccountKeepsRowButErasesPersonalData() {
    long g = newGroup("Удаление");
    TestUser u = newUser(g, "student");
    assertThat(u.api().delete("/api/me", Map.of("password", "wrong-password")).status())
        .isEqualTo(400);
    assertThat(u.api().delete("/api/me", Map.of("password", PASSWORD)).status()).isEqualTo(200);
    var login =
        client().post("/api/auth/login", Map.of("username", u.username(), "password", PASSWORD));
    assertThat(login.status()).isEqualTo(401);
    var users = admin().get("/api/admin/users").json();
    for (JsonNode n : users) {
      assertThat(n.get("username").asString()).isNotEqualTo(u.username());
    }
    assertThat(admin().get("/api/groups/" + g + "/members").json().size()).isZero();
    clock.advance(Duration.ofMinutes(16));
  }

  @Test
  void lastAdminCannotLeave() {
    var r = admin().delete("/api/me", Map.of("password", ADMIN_PASSWORD));
    assertThat(r.status()).isEqualTo(409);
    assertThat(r.error()).isEqualTo("last_admin");
  }

  @Test
  void singleModeForbidsSecondGroup() {
    ApiClient a = admin();
    assertThat(a.patch("/api/admin/settings", Map.of("mode", "single")).status()).isEqualTo(409);
  }

  @Test
  void directCreationCanBeDisabled() {
    ApiClient a = admin();
    long g = newGroup("Выкл");
    try {
      a.patch("/api/admin/settings", Map.of("directAccounts", false));
      var r =
          a.post(
              "/api/groups/" + g + "/accounts",
              Map.of("accounts", List.of(Map.of("displayName", "Кто-то Такой"))));
      assertThat(r.status()).isEqualTo(403);
    } finally {
      a.patch("/api/admin/settings", Map.of("directAccounts", true));
    }
  }
}
