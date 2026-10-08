package app.groupbase.content;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.IntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Архивы прошлых семестров (0.9.7). */
class SemesterIT extends IntegrationTest {

  private long subject(long group, String name) {
    return admin()
        .post("/api/groups/" + group + "/subjects", Map.of("name", name))
        .json()
        .get("id")
        .asLong();
  }

  private static boolean has(tools.jackson.databind.JsonNode list, long id) {
    for (var x : list) {
      if (x.get("id").asLong() == id) {
        return true;
      }
    }
    return false;
  }

  @Test
  void archiveKeepsContentAndLeavesCurrentLists() {
    long g = newGroup("Семестры");
    long other = newGroup("Чужая");
    TestUser headman = newUser(g, "headman");
    TestUser student = newUser(g, "student");
    long old = subject(g, "Матанализ " + uniq());
    long cur = subject(g, "Физика " + uniq());
    long foreign = subject(other, "Химия " + uniq());
    long hw =
        headman
            .api()
            .post(
                "/api/homework",
                Map.of("subjectId", old, "title", "Ряды", "dueAt", clock.millis() + 86_400_000L))
            .json()
            .get("id")
            .asLong();
    String url = "/api/groups/" + g + "/semesters";
    assertThat(has(student.api().get("/api/homework").json(), hw)).isTrue();

    assertThat(student.api().post(url, Map.of("name", "Осень", "subjects", List.of(old))).status())
        .isEqualTo(403);
    assertThat(
            headman.api().post(url, Map.of("name", "Осень", "subjects", List.of(foreign))).status())
        .isEqualTo(400);
    var r = headman.api().post(url, Map.of("name", " Осень  2025 ", "subjects", List.of(old)));
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    long sem = r.json().get("id").asLong();
    assertThat(r.json().get("name").asString()).isEqualTo("Осень 2025");

    var s = student.api().get("/api/subjects/" + old).json();
    assertThat(s.get("archived").asBoolean()).isTrue();
    assertThat(s.get("semester").asLong()).isEqualTo(sem);
    assertThat(student.api().get("/api/subjects/" + cur).json().get("archived").asBoolean())
        .isFalse();
    // Задание прошлого семестра – не в общих списках, но на странице предмета осталось.
    assertThat(has(student.api().get("/api/homework").json(), hw)).isFalse();
    assertThat(has(student.api().get("/api/homework?subject=" + old).json(), hw)).isTrue();

    var list = student.api().get(url).json();
    assertThat(list.get("items").size()).isEqualTo(1);
    assertThat(list.get("items").get(0).get("subjects").asInt()).isEqualTo(1);
    assertThat(list.get("suggested").asString()).contains("семестр");

    assertThat(student.api().patch("/api/semesters/" + sem, Map.of("name", "X")).status())
        .isEqualTo(403);
    assertThat(headman.api().patch("/api/semesters/" + sem, Map.of("name", "Осень")).status())
        .isEqualTo(200);
    assertThat(student.api().get(url).json().get("items").get(0).get("name").asString())
        .isEqualTo("Осень");

    // Вернуть предметы: архив исчезает, предмет снова текущий.
    assertThat(student.api().delete("/api/semesters/" + sem).status()).isEqualTo(403);
    assertThat(headman.api().delete("/api/semesters/" + sem).status()).isEqualTo(200);
    s = student.api().get("/api/subjects/" + old).json();
    assertThat(s.get("archived").asBoolean()).isFalse();
    assertThat(s.get("semester").isNull()).isTrue();
    assertThat(student.api().get(url).json().get("items").size()).isZero();
    assertThat(has(student.api().get("/api/homework").json(), hw)).isTrue();
  }

  @Test
  void defaultNameFollowsAcademicYear() {
    assertThat(SemesterService.defaultName(LocalDate.of(2026, 1, 20)))
        .isEqualTo("Осенний семестр 2025");
    assertThat(SemesterService.defaultName(LocalDate.of(2026, 6, 30)))
        .isEqualTo("Весенний семестр 2026");
    assertThat(SemesterService.defaultName(LocalDate.of(2026, 12, 1)))
        .isEqualTo("Осенний семестр 2026");
  }
}
