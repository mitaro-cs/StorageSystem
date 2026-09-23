package app.groupbase.search;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.ApiClient;
import app.groupbase.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class SearchIT extends IntegrationTest {

  private static long subject(ApiClient api, long group, String name) {
    return api.post(
            "/api/groups/" + group + "/subjects", Map.of("name", name, "teacher", "Петрова Е. А."))
        .json()
        .get("id")
        .asLong();
  }

  private long homework(ApiClient api, long subject, long group, String title, String body) {
    var r =
        api.post(
            "/api/homework",
            Map.of(
                "subjectId", subject,
                "title", title,
                "body", body,
                "dueAt", clock.millis() + 86_400_000L,
                "groupIds", List.of(group)));
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    return r.json().get("id").asLong();
  }

  private static List<String> found(ApiClient api, String q) {
    var r = api.get("/api/search?q=" + java.net.URLEncoder.encode(q, StandardCharsets.UTF_8));
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    List<String> out = new ArrayList<>();
    for (JsonNode n : r.json().get("items")) {
      out.add(n.get("kind").asString() + ":" + n.get("id").asLong());
    }
    return out;
  }

  @Test
  void findsByWordFormsOnlyWhatUserMaySee() {
    long a = newGroup("Поиск-А");
    long b = newGroup("Поиск-Б");
    TestUser headman = newUser(a, "headman");
    TestUser student = newUser(a, "student");
    TestUser stranger = newUser(b, "student");
    long s = subject(headman.api(), a, "Математический анализ");
    long hw = homework(headman.api(), s, a, "Типовой расчёт по рядам", "Решить задачи 1–12");
    long hidden = homework(headman.api(), s, a, "Черновик задачи по рядам", "");
    headman.api().put("/api/homework/" + hidden + "/hidden", Map.of("value", true));
    var news =
        headman
            .api()
            .post(
                "/api/news",
                Map.of(
                    "title",
                    "Перенос пары",
                    "body",
                    "Лекция про **ряды Фурье** в 314",
                    "groupIds",
                    List.of(a)));
    long newsId = news.json().get("id").asLong();

    // Формы слова, «ё» и «е», регистр.
    assertThat(found(student.api(), "задача")).contains("homework:" + hw);
    assertThat(found(student.api(), "РАСЧЕТ")).containsExactly("homework:" + hw);
    var title = student.api().get("/api/search?q=%D1%80%D0%B0%D1%81%D1%87%D0%B5%D1%82").json();
    StringBuilder shown = new StringBuilder();
    title.get("items").get(0).get("title").forEach(x -> shown.append(x.get("text").asString()));
    assertThat(shown.toString())
        .as("буква «ё» в выдаче сохраняется")
        .isEqualTo("Типовой расчёт по рядам");
    assertThat(found(student.api(), "ряды")).contains("homework:" + hw, "news:" + newsId);
    assertThat(found(student.api(), "математическому")).containsExactly("subject:" + s);
    assertThat(found(student.api(), "петрова")).containsExactly("subject:" + s);

    // Скрытое видят автор и модераторы, студент — нет; чужая группа не видит ничего.
    assertThat(found(student.api(), "черновик")).isEmpty();
    assertThat(found(headman.api(), "черновик")).containsExactly("homework:" + hidden);
    assertThat(found(stranger.api(), "ряды")).isEmpty();
    assertThat(found(stranger.api(), "математический")).isEmpty();

    // Фильтр по разделу и подсветка совпадений.
    var r = student.api().get("/api/search?q=%D1%80%D1%8F%D0%B4%D1%8B&kind=news").json();
    assertThat(r.get("items").size()).isEqualTo(1);
    JsonNode snippet = r.get("items").get(0).get("snippet");
    boolean anyHit = false;
    for (JsonNode seg : snippet) {
      anyHit |= seg.get("hit").asBoolean();
      assertThat(seg.get("text").asString()).doesNotContain("**");
    }
    assertThat(anyHit).isTrue();
    assertThat(student.api().get("/api/search?q=x&kind=users").status()).isEqualTo(400);
  }

  @Test
  void indexFollowsEditsDeletesAndMaterials() {
    long g = newGroup("Индекс");
    TestUser headman = newUser(g, "headman");
    long s = subject(headman.api(), g, "Физика");
    long hw = homework(headman.api(), s, g, "Лабораторная про маятник", "");
    assertThat(found(headman.api(), "маятника")).containsExactly("homework:" + hw);

    headman
        .api()
        .patch(
            "/api/homework/" + hw,
            Map.of(
                "subjectId",
                s,
                "title",
                "Лабораторная про пружину",
                "body",
                "",
                "dueAt",
                clock.millis() + 86_400_000L,
                "groupIds",
                List.of(g)));
    assertThat(found(headman.api(), "маятник")).isEmpty();
    assertThat(found(headman.api(), "пружины")).containsExactly("homework:" + hw);

    assertThat(headman.api().delete("/api/homework/" + hw).status()).isEqualTo(200);
    assertThat(found(headman.api(), "пружина")).isEmpty();

    long file =
        headman
            .api()
            .upload("Конспект Гюйгенс.pdf", "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.UTF_8))
            .json()
            .get("id")
            .asLong();
    long m =
        headman
            .api()
            .post(
                "/api/subjects/" + s + "/materials",
                Map.of("kind", "file", "fileId", file, "title", "Лекция 3"))
            .json()
            .get("id")
            .asLong();
    assertThat(found(headman.api(), "гюйгенс")).containsExactly("material:" + m);
  }

  @Test
  void strangeInputDoesNotBreakSearch() {
    long g = newGroup("Ввод");
    TestUser student = newUser(g, "student");
    for (String q : List.of("\"", "*", "NEAR(", "a OR", "(((", "-", "^", "ё")) {
      assertThat(
              student
                  .api()
                  .get("/api/search?q=" + java.net.URLEncoder.encode(q, StandardCharsets.UTF_8))
                  .status())
          .as(q)
          .isEqualTo(200);
    }
    assertThat(student.api().get("/api/search?q=" + "a".repeat(201)).status()).isEqualTo(400);
  }
}
