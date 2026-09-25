package app.groupbase.content;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Типы заданий (зачёт, экзамен…), место и режим «Сессия». */
class SessionIT extends IntegrationTest {

  private static final long DAY = Duration.ofDays(1).toMillis();

  private long subject(TestUser u, long group, String name) {
    return u.api()
        .post("/api/groups/" + group + "/subjects", Map.of("name", name))
        .json()
        .get("id")
        .asLong();
  }

  private JsonNode homework(TestUser u, Map<String, Object> fields) {
    var r = u.api().post("/api/homework", fields);
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    return r.json();
  }

  private static List<String> titles(JsonNode arr) {
    List<String> out = new ArrayList<>();
    arr.forEach(n -> out.add(n.get("title").asString()));
    return out;
  }

  @Test
  void examHasKindAndPlaceAndLandsInExamsView() {
    long g = newGroup("Сессия");
    TestUser headman = newUser(g, "headman");
    TestUser student = newUser(g, "student");
    long s = subject(headman, g, "Высшая математика");
    long now = clock.millis();

    JsonNode plain =
        homework(headman, Map.of("subjectId", s, "title", "Задачи", "dueAt", now + DAY));
    assertThat(plain.get("kind").asString()).isEqualTo("homework");
    assertThat(plain.get("place").asString()).isEmpty();

    Map<String, Object> exam = new HashMap<>();
    exam.put("subjectId", s);
    exam.put("title", "Экзамен по матанализу");
    exam.put("dueAt", now + 20 * DAY);
    exam.put("kind", "exam");
    exam.put("place", "  ауд.   305 ");
    JsonNode e = homework(headman, exam);
    assertThat(e.get("kind").asString()).isEqualTo("exam");
    assertThat(e.get("place").asString()).isEqualTo("ауд. 305");
    long examId = e.get("id").asLong();

    homework(
        headman,
        Map.of("subjectId", s, "title", "Зачёт", "dueAt", now + 10 * DAY, "kind", "credit"));
    homework(
        headman, Map.of("subjectId", s, "title", "Лаба", "dueAt", now + 2 * DAY, "kind", "lab"));
    homework(
        headman,
        Map.of("subjectId", s, "title", "Прошлогодний", "dueAt", now - 200 * DAY, "kind", "exam"));

    // Зачёты и экзамены по порядку дат — без обычных заданий и без давно прошедших.
    var exams = student.api().get("/api/homework?view=exams&group=" + g).json();
    assertThat(titles(exams)).containsExactly("Зачёт", "Экзамен по матанализу");
    var today = student.api().get("/api/today?group=" + g).json();
    assertThat(titles(today.get("exams"))).containsExactly("Зачёт", "Экзамен по матанализу");

    // Правка без kind тип не меняет; place: "" — убирает место.
    var edited =
        headman.api().patch("/api/homework/" + examId, Map.of("title", "Экзамен", "place", ""));
    assertThat(edited.json().get("kind").asString()).isEqualTo("exam");
    assertThat(edited.json().get("place").asString()).isEmpty();

    assertThat(
            headman
                .api()
                .post(
                    "/api/homework",
                    Map.of("subjectId", s, "title", "x", "dueAt", now + DAY, "kind", "quiz"))
                .status())
        .isEqualTo(400);
    assertThat(
            headman
                .api()
                .post(
                    "/api/homework",
                    Map.of(
                        "subjectId", s, "title", "x", "dueAt", now + DAY, "place", "я".repeat(81)))
                .status())
        .isEqualTo(400);

    // Уведомление говорит, что это экзамен, и где он.
    var note = student.api().get("/api/notifications").json().get("items");
    List<String> noteTitles = new ArrayList<>();
    note.forEach(n -> noteTitles.add(n.get("title").asString()));
    assertThat(noteTitles).contains("Экзамен · Высшая математика", "Зачёт · Высшая математика");
  }

  @Test
  void headmanSetsSessionDatesStudentsSeeThem() {
    long g = newGroup("Даты сессии");
    TestUser headman = newUser(g, "headman");
    TestUser student = newUser(g, "student");
    ZoneId zone = props.timezone();
    long from = clock.millis() + 30 * DAY;
    long to = from + 16 * DAY;

    var set = headman.api().put("/api/groups/" + g + "/session", Map.of("from", from, "to", to));
    assertThat(set.status()).as(set.body()).isEqualTo(200);

    JsonNode mine = null;
    for (JsonNode gr : student.api().get("/api/me").json().get("groups")) {
      if (gr.get("id").asLong() == g) {
        mine = gr;
      }
    }
    assertThat(mine).isNotNull();
    long savedFrom = mine.get("session").get("from").asLong();
    // Дни хранятся полночью по часовому поясу сайта.
    var local = Instant.ofEpochMilli(savedFrom).atZone(zone);
    assertThat(local.getHour()).isZero();
    assertThat(local.toLocalDate())
        .isEqualTo(Instant.ofEpochMilli(from).atZone(zone).toLocalDate());

    assertThat(
            student
                .api()
                .put("/api/groups/" + g + "/session", Map.of("from", from, "to", to))
                .status())
        .isEqualTo(403);
    assertThat(
            headman
                .api()
                .put("/api/groups/" + g + "/session", Map.of("from", to, "to", from))
                .status())
        .isEqualTo(400);
    assertThat(
            headman
                .api()
                .put("/api/groups/" + g + "/session", Map.of("from", from, "to", from + 90 * DAY))
                .status())
        .isEqualTo(400);
    Map<String, Object> clear = new HashMap<>();
    clear.put("from", null);
    clear.put("to", null);
    assertThat(headman.api().put("/api/groups/" + g + "/session", clear).status()).isEqualTo(200);
    for (JsonNode gr : student.api().get("/api/me").json().get("groups")) {
      if (gr.get("id").asLong() == g) {
        assertThat(gr.get("session").isNull()).isTrue();
      }
    }
  }

  @Test
  void headmanChoosesWhenSessionButtonIsShown() {
    long g = newGroup("Кнопка сессии");
    TestUser headman = newUser(g, "headman");
    TestUser student = newUser(g, "student");
    String path = "/api/groups/" + g + "/session-nav";

    // По умолчанию — только около сессии: кнопка нужна пару раз в год.
    assertThat(
            student.api().get("/api/me").json().get("groups").get(0).get("sessionNav").asString())
        .isEqualTo("auto");
    for (String mode : List.of("show", "hide", "auto")) {
      var r = headman.api().put(path, Map.of("mode", mode));
      assertThat(r.status()).as(r.body()).isEqualTo(200);
      assertThat(r.json().get("sessionNav").asString()).isEqualTo(mode);
    }
    headman.api().put(path, Map.of("mode", "hide"));
    assertThat(
            student.api().get("/api/me").json().get("groups").get(0).get("sessionNav").asString())
        .isEqualTo("hide");
    assertThat(headman.api().put(path, Map.of("mode", "sometimes")).status()).isEqualTo(400);
    assertThat(student.api().put(path, Map.of("mode", "show")).status()).isEqualTo(403);
  }
}
