package app.groupbase.sync;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.ApiClient;
import app.groupbase.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class SyncIT extends IntegrationTest {

  private static JsonNode sync(ApiClient api, long after) {
    var r = api.get("/api/sync?after=" + after);
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    return r.json();
  }

  private static List<Long> ids(JsonNode arr) {
    List<Long> out = new ArrayList<>();
    arr.forEach(n -> out.add(n.isNumber() ? n.asLong() : n.get("id").asLong()));
    return out;
  }

  private long homework(ApiClient api, long subject, long group, String title) {
    var r =
        api.post(
            "/api/homework",
            Map.of(
                "subjectId",
                subject,
                "title",
                title,
                "body",
                "",
                "dueAt",
                clock.millis() + 86_400_000L,
                "groupIds",
                List.of(group)));
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    return r.json().get("id").asLong();
  }

  @Test
  void firstSyncIsFullSnapshotOfWhatUserSees() {
    long g = newGroup("Синхр");
    long other = newGroup("Чужая синхр");
    TestUser headman = newUser(g, "headman");
    TestUser student = newUser(g, "student");
    TestUser stranger = newUser(other, "headman");
    long s =
        headman
            .api()
            .post("/api/groups/" + g + "/subjects", Map.of("name", "Физика"))
            .json()
            .get("id")
            .asLong();
    long hw = homework(headman.api(), s, g, "Лабораторная");
    long hidden = homework(headman.api(), s, g, "Черновик");
    headman.api().put("/api/homework/" + hidden + "/hidden", Map.of("value", true));
    long news =
        headman
            .api()
            .post("/api/news", Map.of("title", "Перенос", "body", "", "groupIds", List.of(g)))
            .json()
            .get("id")
            .asLong();
    student.api().post("/api/news/" + news + "/comments", Map.of("body", "Ок"));
    long folder =
        headman
            .api()
            .post("/api/subjects/" + s + "/folders", Map.of("name", "Лекции"))
            .json()
            .get("id")
            .asLong();
    headman
        .api()
        .post(
            "/api/subjects/" + s + "/materials",
            Map.of(
                "kind",
                "link",
                "url",
                "https://example.org",
                "title",
                "Учебник",
                "folderId",
                folder));
    long foreignSubject =
        stranger
            .api()
            .post("/api/groups/" + other + "/subjects", Map.of("name", "Чужой"))
            .json()
            .get("id")
            .asLong();
    homework(stranger.api(), foreignSubject, other, "Чужое задание");

    JsonNode full = sync(student.api(), 0);
    assertThat(full.get("full").asBoolean()).isTrue();
    assertThat(full.get("cursor").asLong()).isPositive();
    assertThat(ids(full.get("subjects"))).containsExactly(s);
    assertThat(ids(full.get("homework").get("upsert"))).containsExactly(hw);
    assertThat(ids(full.get("news").get("upsert"))).containsExactly(news);
    assertThat(full.get("materials").get("upsert").get(0).get("title").asString())
        .isEqualTo("Учебник");
    assertThat(ids(full.get("folders").get("upsert"))).containsExactly(folder);
    assertThat(full.get("members").get(String.valueOf(g)).size()).isEqualTo(2);
    assertThat(full.get("members").get(String.valueOf(g)).get(0).get("username").isNull()).isTrue();
    assertThat(full.get("comments").get(0).get("type").asString()).isEqualTo("post");
    assertThat(full.get("comments").get(0).get("items").get(0).get("bodyHtml").asString())
        .contains("Ок");

    JsonNode head = sync(headman.api(), 0);
    assertThat(ids(head.get("homework").get("upsert")))
        .as("староста видит скрытое")
        .contains(hw, hidden);
  }

  @Test
  void incrementalSyncBringsOnlyChangesAndRespectsRights() {
    long g = newGroup("Дельта");
    long other = newGroup("Чужая дельта");
    TestUser headman = newUser(g, "headman");
    TestUser student = newUser(g, "student");
    TestUser stranger = newUser(other, "student");
    long s =
        headman
            .api()
            .post("/api/groups/" + g + "/subjects", Map.of("name", "Химия"))
            .json()
            .get("id")
            .asLong();
    long hw = homework(headman.api(), s, g, "Реферат");
    long cursor = sync(student.api(), 0).get("cursor").asLong();
    long strangerCursor = sync(stranger.api(), 0).get("cursor").asLong();

    // Ничего не менялось — пусто.
    JsonNode none = sync(student.api(), cursor);
    assertThat(none.get("full").asBoolean()).isFalse();
    assertThat(none.get("homework").get("upsert").size()).isZero();
    assertThat(none.get("cursor").asLong()).isEqualTo(cursor);

    // Правка, отметка «сделано», комментарий.
    headman
        .api()
        .patch(
            "/api/homework/" + hw,
            Map.of(
                "subjectId",
                s,
                "title",
                "Реферат про полимеры",
                "body",
                "",
                "dueAt",
                clock.millis() + 86_400_000L,
                "groupIds",
                List.of(g)));
    student.api().put("/api/homework/" + hw + "/done", Map.of("value", true));
    student.api().post("/api/homework/" + hw + "/comments", Map.of("body", "Сколько страниц?"));
    JsonNode d = sync(student.api(), cursor);
    assertThat(d.get("full").asBoolean()).isFalse();
    JsonNode item = d.get("homework").get("upsert").get(0);
    assertThat(item.get("title").asString()).isEqualTo("Реферат про полимеры");
    assertThat(item.get("done").asBoolean()).isTrue();
    assertThat(item.get("comments").asInt()).isEqualTo(1);
    assertThat(d.get("comments").get(0).get("id").asLong()).isEqualTo(hw);
    cursor = d.get("cursor").asLong();

    // Скрыли — студенту «удалить», старосте — обновить.
    long headCursor = cursor;
    headman.api().put("/api/homework/" + hw + "/hidden", Map.of("value", true));
    assertThat(ids(sync(student.api(), cursor).get("homework").get("delete"))).containsExactly(hw);
    assertThat(ids(sync(headman.api(), headCursor).get("homework").get("upsert")))
        .containsExactly(hw);

    // Чужая группа не узнаёт даже номеров изменившегося.
    JsonNode foreign = sync(stranger.api(), strangerCursor);
    assertThat(foreign.get("homework").get("upsert").size()).isZero();
    assertThat(foreign.get("homework").get("delete").size()).isZero();

    // Удалили — всем «удалить».
    headman.api().delete("/api/homework/" + hw);
    assertThat(ids(sync(headman.api(), headCursor).get("homework").get("delete")))
        .containsExactly(hw);
  }

  @Test
  void membershipChangeOrStaleCursorGivesFullSnapshot() {
    long g = newGroup("Сброс");
    TestUser headman = newUser(g, "headman");
    TestUser student = newUser(g, "student");
    long cursor = sync(student.api(), 0).get("cursor").asLong();
    headman
        .api()
        .put("/api/groups/" + g + "/members/" + student.id() + "/role", Map.of("role", "deputy"));
    assertThat(sync(student.api(), cursor).get("full").asBoolean()).as("сменилась роль").isTrue();
    assertThat(sync(student.api(), cursor + 1_000_000).get("full").asBoolean())
        .as("курсор из будущего (сервер пересоздан)")
        .isTrue();
  }
}
