package app.groupbase.content;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.ApiClient;
import app.groupbase.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Закреплённые чаты Telegram: у группы (на главной) и у предмета. */
class ChatsIT extends IntegrationTest {

  private static JsonNode group(ApiClient c, long id) {
    for (JsonNode g : c.get("/api/me").json().get("groups")) {
      if (g.get("id").asLong() == id) {
        return g;
      }
    }
    throw new AssertionError("нет группы " + id);
  }

  @Test
  void headmanPinsChatsEveryoneSeesThem() {
    ApiClient admin = admin();
    long g = newGroup("Чаты");
    var headman = newUser(g, "headman").api();
    var student = newUser(g, "student").api();

    var add = headman.post("/api/groups/" + g + "/chats", Map.of("url", "@bin2509_chat"));
    assertThat(add.status()).as(add.body()).isEqualTo(200);
    assertThat(add.json().get(0).get("title").asString()).isEqualTo("Чат группы");
    assertThat(add.json().get(0).get("url").asString()).isEqualTo("https://t.me/bin2509_chat");
    headman.post(
        "/api/groups/" + g + "/chats",
        Map.of("title", "Канал с объявлениями", "url", "https://t.me/+AbCdEf12345"));

    JsonNode chats = group(student, g).get("chats");
    assertThat(chats.size()).isEqualTo(2);
    assertThat(chats.get(1).get("title").asString()).isEqualTo("Канал с объявлениями");

    // Студент закреплять не может; ссылки не на Telegram не принимаются.
    assertThat(student.post("/api/groups/" + g + "/chats", Map.of("url", "@x_chat_1")).status())
        .isEqualTo(403);
    var vk = headman.post("/api/groups/" + g + "/chats", Map.of("url", "https://vk.com/club1"));
    assertThat(vk.status()).isEqualTo(400);
    assertThat(vk.json().get("message").asString()).contains("Telegram");

    long id = chats.get(0).get("id").asLong();
    var edit =
        headman.put(
            "/api/groups/" + g + "/chats/" + id,
            Map.of("title", "Основной чат", "url", "t.me/bin2509_chat"));
    assertThat(edit.status()).isEqualTo(200);
    assertThat(group(student, g).get("chats").get(0).get("title").asString())
        .isEqualTo("Основной чат");

    // Чужую группу через свой id чата не тронуть.
    long other = newGroup("Чужая");
    assertThat(admin.delete("/api/groups/" + other + "/chats/" + id).status()).isEqualTo(404);
    assertThat(headman.delete("/api/groups/" + g + "/chats/" + id).status()).isEqualTo(200);
    assertThat(group(student, g).get("chats").size()).isEqualTo(1);
  }

  @Test
  void subjectHasItsOwnChat() {
    admin();
    long g = newGroup("Предметы с чатом");
    var headman = newUser(g, "headman").api();
    var created =
        headman.post(
            "/api/groups/" + g + "/subjects",
            Map.of("name", "Матанализ", "color", "#3355ff", "chatUrl", "@matan_bin2509"));
    assertThat(created.status()).as(created.body()).isEqualTo(200);
    long id = created.json().get("id").asLong();
    assertThat(created.json().get("chatUrl").asString()).isEqualTo("https://t.me/matan_bin2509");

    // Без поля chatUrl ссылка не меняется, пустая строка — убирает её.
    var renamed =
        headman.patch("/api/subjects/" + id, Map.of("name", "Матанализ 2", "color", "#3355ff"));
    assertThat(renamed.json().get("chatUrl").asString()).isEqualTo("https://t.me/matan_bin2509");
    var cleared =
        headman.patch(
            "/api/subjects/" + id,
            Map.of("name", "Матанализ 2", "color", "#3355ff", "chatUrl", ""));
    assertThat(cleared.json().get("chatUrl").isNull()).isTrue();

    var bad =
        headman.patch(
            "/api/subjects/" + id,
            Map.of("name", "Матанализ 2", "color", "#3355ff", "chatUrl", "https://evil.ru"));
    assertThat(bad.status()).isEqualTo(400);
  }
}
