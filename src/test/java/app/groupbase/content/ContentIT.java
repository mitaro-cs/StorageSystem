package app.groupbase.content;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.ApiClient;
import app.groupbase.IntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ContentIT extends IntegrationTest {

  private long subject(ApiClient api, long group, String name) {
    var r =
        api.post("/api/groups/" + group + "/subjects", Map.of("name", name, "color", "#3b82f6"));
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    return r.json().get("id").asLong();
  }

  private static List<Long> ids(JsonNode arr) {
    return java.util.stream.StreamSupport.stream(arr.spliterator(), false)
        .map(n -> n.get("id").asLong())
        .toList();
  }

  @Test
  void subjectsAreManagedByHeadmanNotByStudentOrDeputy() {
    long g = newGroup("Предметы");
    TestUser headman = newUser(g, "headman");
    TestUser deputy = newUser(g, "deputy");
    TestUser student = newUser(g, "student");
    long s = subject(headman.api(), g, "Матанализ");
    assertThat(deputy.api().post("/api/groups/" + g + "/subjects", Map.of("name", "X")).status())
        .isEqualTo(403);
    assertThat(student.api().post("/api/groups/" + g + "/subjects", Map.of("name", "X")).status())
        .isEqualTo(403);
    var list = student.api().get("/api/subjects").json();
    assertThat(ids(list)).contains(s);
    assertThat(list.get(0).get("can").get("edit").asBoolean()).isFalse();
    assertThat(
            headman
                .api()
                .patch("/api/subjects/" + s, Map.of("name", "Матан", "teacher", "Иванов И. И."))
                .json()
                .get("teacher")
                .asString())
        .isEqualTo("Иванов И. И.");
    assertThat(student.api().put("/api/subjects/" + s + "/pinned", Map.of("value", true)).status())
        .isEqualTo(200);
    assertThat(student.api().get("/api/subjects/" + s).json().get("pinned").asBoolean()).isTrue();
  }

  @Test
  void sharingBetweenHeadmenNeedsConfirmation() {
    long a = newGroup("Поток-A");
    long b = newGroup("Поток-B");
    TestUser headA = newUser(a, "headman");
    TestUser headB = newUser(b, "headman");
    TestUser studentB = newUser(b, "student");
    long s = subject(headA.api(), a, "Лекции потока");

    assertThat(studentB.api().get("/api/subjects/" + s).status()).isEqualTo(404);
    var req = headA.api().post("/api/subjects/" + s + "/links", Map.of("groupId", b));
    assertThat(req.json().get("status").asString()).isEqualTo("requested");
    long reqId = req.json().get("requestId").asLong();
    assertThat(headA.api().post("/api/link-requests/" + reqId + "/accept", null).status())
        .isEqualTo(403);
    var incoming = headB.api().get("/api/link-requests").json();
    assertThat(ids(incoming)).contains(reqId);
    assertThat(headB.api().post("/api/link-requests/" + reqId + "/accept", null).status())
        .isEqualTo(200);

    var seen = studentB.api().get("/api/subjects/" + s);
    assertThat(seen.status()).isEqualTo(200);
    assertThat(seen.json().get("groups").size()).isEqualTo(2);

    // Отвязать последнюю группу нельзя; свою — можно.
    assertThat(headB.api().delete("/api/subjects/" + s + "/links/" + b).status()).isEqualTo(200);
    assertThat(headA.api().delete("/api/subjects/" + s + "/links/" + a).status()).isEqualTo(409);
  }

  @Test
  void adminLinksDirectly() {
    long a = newGroup("Адм-A");
    long b = newGroup("Адм-B");
    long s = subject(admin(), a, "Физика");
    var r = admin().post("/api/subjects/" + s + "/links", Map.of("groupId", b));
    assertThat(r.json().get("status").asString()).isEqualTo("linked");
  }

  @Test
  void newsInSharedSubjectCanTargetOneGroup() {
    long a = newGroup("Н-A");
    long b = newGroup("Н-B");
    TestUser headA = newUser(a, "headman");
    TestUser studentA = newUser(a, "student");
    TestUser studentB = newUser(b, "student");
    long s = subject(admin(), a, "Общий");
    admin().post("/api/subjects/" + s + "/links", Map.of("groupId", b));

    var onlyA =
        headA
            .api()
            .post(
                "/api/news",
                Map.of(
                    "title",
                    "Только для A",
                    "body",
                    "Текст",
                    "subjectId",
                    s,
                    "groupIds",
                    List.of(a)));
    assertThat(onlyA.status()).as(onlyA.body()).isEqualTo(200);
    long id = onlyA.json().get("id").asLong();
    // Староста A не может адресовать новость группе B.
    assertThat(
            headA
                .api()
                .post(
                    "/api/news",
                    Map.of("title", "Обоим", "subjectId", s, "groupIds", List.of(a, b)))
                .status())
        .isEqualTo(403);

    assertThat(ids(studentA.api().get("/api/news").json().get("items"))).contains(id);
    assertThat(ids(studentB.api().get("/api/news").json().get("items"))).doesNotContain(id);
    assertThat(studentB.api().get("/api/news/" + id).status()).isEqualTo(404);
    assertThat(
            studentB.api().post("/api/news/" + id + "/comments", Map.of("body", "я тут")).status())
        .isEqualTo(404);

    // Без выбора групп новость в общем предмете уходит всем группам, где у автора есть право.
    var all = admin().post("/api/news", Map.of("title", "Всем", "subjectId", s));
    assertThat(all.json().get("groups").size()).isEqualTo(2);
  }

  @Test
  void studentsCannotPublishDeputiesCan() {
    long g = newGroup("Публикации");
    TestUser deputy = newUser(g, "deputy");
    TestUser student = newUser(g, "student");
    long s = subject(admin(), g, "Английский");
    long due = clock.millis() + Duration.ofDays(2).toMillis();
    assertThat(
            student.api().post("/api/news", Map.of("title", "x", "groupIds", List.of(g))).status())
        .isEqualTo(403);
    assertThat(
            student
                .api()
                .post("/api/homework", Map.of("subjectId", s, "title", "x", "dueAt", due))
                .status())
        .isEqualTo(403);
    assertThat(
            deputy.api().post("/api/news", Map.of("title", "x", "groupIds", List.of(g))).status())
        .isEqualTo(200);
    assertThat(
            deputy
                .api()
                .post("/api/homework", Map.of("subjectId", s, "title", "x", "dueAt", due))
                .status())
        .isEqualTo(200);
  }

  @Test
  void feedPaginatesWithCursorAndSeparatesPinned() {
    long g = newGroup("Лента");
    TestUser student = newUser(g, "student");
    ApiClient a = admin();
    for (int i = 0; i < 25; i++) {
      a.post("/api/news", Map.of("title", "Новость " + i, "groupIds", List.of(g)));
    }
    long pinned =
        a.post("/api/news", Map.of("title", "Важно", "groupIds", List.of(g), "pinned", true))
            .json()
            .get("id")
            .asLong();
    var first = student.api().get("/api/news?group=" + g).json();
    assertThat(ids(first.get("pinned"))).containsExactly(pinned);
    assertThat(first.get("items").size()).isEqualTo(20);
    long next = first.get("next").asLong();
    var second = student.api().get("/api/news?group=" + g + "&before=" + next).json();
    assertThat(second.get("items").size()).isEqualTo(5);
    assertThat(second.get("next").isNull()).isTrue();
    assertThat(second.get("pinned").size()).isZero();
  }

  @Test
  void homeworkViewsAndPersonalDone() {
    long g = newGroup("ДЗ");
    TestUser s1 = newUser(g, "student");
    TestUser s2 = newUser(g, "student");
    long subj = subject(admin(), g, "Программирование");
    long now = clock.millis();
    long soon =
        admin()
            .post(
                "/api/homework",
                Map.of(
                    "subjectId",
                    subj,
                    "title",
                    "Лаба 1",
                    "body",
                    "Сделать **всё**",
                    "dueAt",
                    now + Duration.ofDays(1).toMillis()))
            .json()
            .get("id")
            .asLong();
    long late =
        admin()
            .post(
                "/api/homework",
                Map.of(
                    "subjectId",
                    subj,
                    "title",
                    "Лаба 0",
                    "dueAt",
                    now - Duration.ofDays(1).toMillis()))
            .json()
            .get("id")
            .asLong();

    var week = s1.api().get("/api/homework?view=week&group=" + g).json();
    assertThat(ids(week)).contains(soon).doesNotContain(late);
    assertThat(week.get(0).get("bodyHtml").asString()).contains("<strong>всё</strong>");
    assertThat(ids(s1.api().get("/api/homework?view=overdue&group=" + g).json())).contains(late);

    assertThat(s1.api().put("/api/homework/" + late + "/done", Map.of("value", true)).status())
        .isEqualTo(200);
    assertThat(ids(s1.api().get("/api/homework?view=overdue&group=" + g).json()))
        .doesNotContain(late);
    // Отметка личная: у второго студента задание всё ещё просрочено.
    assertThat(ids(s2.api().get("/api/homework?view=overdue&group=" + g).json())).contains(late);
    assertThat(s2.api().get("/api/homework/" + late).json().get("done").asBoolean()).isFalse();

    long from = now - Duration.ofDays(3).toMillis();
    long to = now + Duration.ofDays(3).toMillis();
    var range =
        s1.api().get("/api/homework?view=range&group=" + g + "&from=" + from + "&to=" + to).json();
    assertThat(ids(range)).contains(soon, late);
    assertThat(s1.api().get("/api/homework?view=range&from=1&to=2").status()).isEqualTo(200);
    assertThat(s1.api().get("/api/homework?view=bogus").status()).isEqualTo(400);

    var today = s1.api().get("/api/today?group=" + g).json();
    assertThat(ids(today.get("upcoming"))).contains(soon);
  }

  @Test
  void moderationHidesFromStudentsButNotFromAuthor() {
    long g = newGroup("Модерация");
    TestUser headman = newUser(g, "headman");
    TestUser deputy = newUser(g, "deputy");
    TestUser student = newUser(g, "student");
    long id =
        deputy
            .api()
            .post("/api/news", Map.of("title", "Спорное", "groupIds", List.of(g)))
            .json()
            .get("id")
            .asLong();
    assertThat(deputy.api().put("/api/news/" + id + "/hidden", Map.of("value", true)).status())
        .isEqualTo(403);
    assertThat(headman.api().put("/api/news/" + id + "/hidden", Map.of("value", true)).status())
        .isEqualTo(200);
    assertThat(student.api().get("/api/news/" + id).status()).isEqualTo(404);
    assertThat(deputy.api().get("/api/news/" + id).json().get("hidden").asBoolean()).isTrue();
    assertThat(ids(student.api().get("/api/news?group=" + g).json().get("items")))
        .doesNotContain(id);

    // Автор может удалить своё, студент — нет.
    assertThat(student.api().delete("/api/news/" + id).status()).isEqualTo(403);
    assertThat(deputy.api().delete("/api/news/" + id).status()).isEqualTo(200);
  }

  @Test
  void commentsFollowParentVisibility() {
    long g = newGroup("Комменты");
    TestUser s1 = newUser(g, "student");
    TestUser s2 = newUser(g, "student");
    TestUser headman = newUser(g, "headman");
    long id =
        admin()
            .post("/api/news", Map.of("title", "Обсуждение", "groupIds", List.of(g)))
            .json()
            .get("id")
            .asLong();
    var c = s1.api().post("/api/news/" + id + "/comments", Map.of("body", "<b>привет</b>"));
    assertThat(c.status()).isEqualTo(200);
    assertThat(c.json().get("bodyHtml").asString()).doesNotContain("<b>");
    long cid = c.json().get("id").asLong();
    var list = s2.api().get("/api/news/" + id + "/comments").json();
    assertThat(list.size()).isEqualTo(1);
    assertThat(list.get(0).get("canDelete").asBoolean()).isFalse();
    assertThat(s2.api().delete("/api/comments/" + cid).status()).isEqualTo(403);
    assertThat(headman.api().delete("/api/comments/" + cid).status()).isEqualTo(200);
    assertThat(admin().get("/api/news/" + id).json().get("comments").asInt()).isZero();
  }

  @Test
  void deletedAuthorShowsAsDeletedUser() {
    long g = newGroup("Автор");
    TestUser deputy = newUser(g, "deputy");
    long id =
        deputy
            .api()
            .post("/api/news", Map.of("title", "Прощальное", "groupIds", List.of(g)))
            .json()
            .get("id")
            .asLong();
    deputy.api().delete("/api/me", Map.of("password", PASSWORD));
    var author = admin().get("/api/news/" + id).json().get("author");
    assertThat(author.get("deleted").asBoolean()).isTrue();
    assertThat(author.get("displayName").asString()).isEmpty();
  }
}
