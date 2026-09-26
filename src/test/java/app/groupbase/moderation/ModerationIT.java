package app.groupbase.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.ApiClient;
import app.groupbase.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * «Модерация»: жалобы участников, свежие и скрытые комментарии, журнал — для тех, кто модерирует
 * (администратор и модератор сайта — во всех группах, староста — в своей).
 */
class ModerationIT extends IntegrationTest {

  private static long id(ApiClient.Response r) {
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    return r.json().get("id").asLong();
  }

  /** Карточка (или жалоба на неё) из ответа модерации; null — нет. */
  private static JsonNode find(JsonNode list, String type, long id) {
    for (JsonNode n : list) {
      JsonNode t = n.has("target") ? n.get("target") : n;
      if (t.get("type").asString().equals(type) && t.get("id").asLong() == id) {
        return n;
      }
    }
    return null;
  }

  @Test
  void studentReportsAndModeratorHides() {
    long g = newGroup("Модерация");
    TestUser student = newUser(g, "student");
    TestUser other = newUser(g, "student");
    ApiClient admin = admin();
    long post =
        id(
            admin.post(
                "/api/news",
                Map.of("title", "Спорная новость", "body", "текст", "groupIds", List.of(g))));

    // Раздел — только тем, кто модерирует.
    assertThat(student.api().get("/api/moderation/summary").status()).isEqualTo(403);
    assertThat(student.api().get("/api/moderation/reports").status()).isEqualTo(403);
    assertThat(student.api().get("/api/moderation/comments").status()).isEqualTo(403);

    // Пожаловаться может каждый, кому видно; повтор от того же человека не считается.
    var report = Map.of("type", "post", "id", post, "reason", "Реклама");
    assertThat(student.api().post("/api/reports", report).status()).isEqualTo(200);
    assertThat(student.api().post("/api/reports", report).status()).isEqualTo(200);
    assertThat(other.api().post("/api/reports", Map.of("type", "post", "id", post)).status())
        .isEqualTo(200);
    // На своё жаловаться не нужно, на невидимое — нельзя.
    long own =
        id(student.api().post("/api/news/" + post + "/comments", Map.of("body", "мой отзыв")));
    assertThat(student.api().post("/api/reports", Map.of("type", "comment", "id", own)).status())
        .isEqualTo(400);
    assertThat(student.api().post("/api/reports", Map.of("type", "post", "id", 999_999)).status())
        .isEqualTo(404);

    JsonNode r = find(admin.get("/api/moderation/reports").json(), "post", post);
    assertThat(r).isNotNull();
    assertThat(r.get("count").asInt()).isEqualTo(2);
    assertThat(r.get("reasons").toString()).contains("Реклама");
    assertThat(r.get("target").get("title").asString()).isEqualTo("Спорная новость");
    assertThat(r.toString()).as("кто пожаловался, не видно").doesNotContain(student.username());
    assertThat(admin.get("/api/moderation/summary").json().get("reports").asInt()).isPositive();

    // Скрыть: у участников новости нет, жалобы закрыты, она в «Скрытом» и в журнале.
    var hide = Map.of("type", "post", "id", post, "action", "hide");
    assertThat(admin.post("/api/moderation/reports/resolve", hide).status()).isEqualTo(200);
    assertThat(student.api().get("/api/news/" + post).status()).isEqualTo(404);
    assertThat(find(admin.get("/api/moderation/reports").json(), "post", post)).isNull();
    assertThat(find(admin.get("/api/moderation/hidden").json(), "post", post)).isNotNull();
    assertThat(admin.get("/api/moderation/log").json().toString())
        .contains("news.hide")
        .contains("Спорная новость");
  }

  @Test
  void moderatorHidesCommentsAndDeletesByReport() {
    long g = newGroup("Обсуждение");
    TestUser student = newUser(g, "student");
    TestUser other = newUser(g, "student");
    ApiClient admin = admin();
    long post =
        id(admin.post("/api/news", Map.of("title", "Новость для споров", "groupIds", List.of(g))));
    long c =
        id(
            student
                .api()
                .post("/api/news/" + post + "/comments", Map.of("body", "Грубый комментарий")));

    // Свежие комментарии — с тем, к чему они.
    JsonNode item = find(admin.get("/api/moderation/comments").json(), "comment", c);
    assertThat(item).isNotNull();
    assertThat(item.get("title").asString()).isEqualTo("Новость для споров");
    assertThat(item.get("parentType").asString()).isEqualTo("post");
    assertThat(item.get("href").asString()).isEqualTo("/news/" + post);
    assertThat(item.get("text").asString()).isEqualTo("Грубый комментарий");

    // Скрыть может только модератор; скрытый видят модераторы и автор.
    var on = Map.of("value", true);
    assertThat(other.api().put("/api/comments/" + c + "/hidden", on).status()).isEqualTo(403);
    assertThat(admin.put("/api/comments/" + c + "/hidden", on).status()).isEqualTo(200);
    assertThat(other.api().get("/api/news/" + post + "/comments").json().size()).isZero();
    JsonNode mine = student.api().get("/api/news/" + post + "/comments").json().get(0);
    assertThat(mine.get("hidden").asBoolean()).isTrue();
    assertThat(mine.get("canHide").asBoolean()).isFalse();
    assertThat(
            admin.get("/api/news/" + post + "/comments").json().get(0).get("canHide").asBoolean())
        .isTrue();
    assertThat(find(admin.get("/api/moderation/hidden").json(), "comment", c)).isNotNull();

    // Вернуть — снова виден всем.
    admin.put("/api/comments/" + c + "/hidden", Map.of("value", false));
    assertThat(other.api().get("/api/news/" + post + "/comments").json().size()).isEqualTo(1);

    // Жалоба на комментарий → удалить: его нет, а в журнале видно, что это было.
    other.api().post("/api/reports", Map.of("type", "comment", "id", c, "reason", "Оскорбление"));
    var delete = Map.of("type", "comment", "id", c, "action", "delete");
    assertThat(admin.post("/api/moderation/reports/resolve", delete).status()).isEqualTo(200);
    assertThat(other.api().get("/api/news/" + post + "/comments").json().size()).isZero();
    assertThat(admin.get("/api/moderation/log").json().toString())
        .contains("comment.delete")
        .contains("Грубый комментарий");
  }

  @Test
  void siteModeratorModeratesEveryGroupAndIsNotified() throws InterruptedException {
    long g = newGroup("Поток");
    TestUser student = newUser(g, "student");
    // Модератор сайта из другой группы: в этой он не состоит.
    TestUser mod = newUser(newGroup("Другая"), "student");
    var role =
        admin().put("/api/admin/users/" + mod.id() + "/instance-role", Map.of("role", "moderator"));
    assertThat(role.status()).as(role.body()).isEqualTo(200);
    // Смена роли завершает сеансы — модератор входит заново.
    ApiClient m = login(mod.username(), PASSWORD);
    long post =
        id(admin().post("/api/news", Map.of("title", "Новость потока", "groupIds", List.of(g))));

    student.api().post("/api/reports", Map.of("type", "post", "id", post, "reason", "Не по теме"));
    var reports = m.get("/api/moderation/reports");
    assertThat(reports.status()).as(reports.body()).isEqualTo(200);
    assertThat(find(reports.json(), "post", post)).isNotNull();

    // Уведомление о жалобе — в колокольчик (рассылка идёт в фоне).
    long until = System.currentTimeMillis() + 5000;
    JsonNode note = null;
    while (note == null && System.currentTimeMillis() < until) {
      for (JsonNode n : m.get("/api/notifications").json().get("items")) {
        if (n.get("kind").asString().equals("report")) {
          note = n;
        }
      }
      if (note == null) {
        Thread.sleep(100);
      }
    }
    assertThat(note).isNotNull();
    assertThat(note.get("url").asString()).isEqualTo("/moderation");
    assertThat(note.get("title").asString()).contains("Новость потока");

    // Оставить как есть: жалоба закрыта, новость на месте.
    var keep = Map.of("type", "post", "id", post, "action", "dismiss");
    assertThat(m.post("/api/moderation/reports/resolve", keep).status()).isEqualTo(200);
    assertThat(find(m.get("/api/moderation/reports").json(), "post", post)).isNull();
    assertThat(student.api().get("/api/news/" + post).status()).isEqualTo(200);
    assertThat(m.get("/api/moderation/log").json().toString()).contains("report.dismiss");

    // Староста модерирует свою группу; зам старосты и студент — нет.
    assertThat(newUser(g, "headman").api().get("/api/moderation/summary").status()).isEqualTo(200);
    assertThat(newUser(g, "deputy").api().get("/api/moderation/summary").status()).isEqualTo(403);
  }
}
