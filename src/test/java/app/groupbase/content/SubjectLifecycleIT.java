package app.groupbase.content;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Подгруппы предмета и удаление предмета насовсем (0.7). */
class SubjectLifecycleIT extends IntegrationTest {

  private long subject(long group, String name) {
    return admin()
        .post("/api/groups/" + group + "/subjects", Map.of("name", name, "teacher", "Петров А. В."))
        .json()
        .get("id")
        .asLong();
  }

  @Test
  void splitIntoSubgroupsKeepsContentInFirst() {
    long g = newGroup("Подгруппы");
    TestUser headman = newUser(g, "headman");
    TestUser student = newUser(g, "student");
    long s = subject(g, "Иностранный язык " + uniq());
    headman
        .api()
        .post(
            "/api/homework",
            Map.of("subjectId", s, "title", "Topic 1", "dueAt", clock.millis() + 86_400_000L));
    assertThat(student.api().post("/api/subjects/" + s + "/subgroups", Map.of("count", 2)).status())
        .isEqualTo(403);
    var r = headman.api().post("/api/subjects/" + s + "/subgroups", Map.of("count", 2));
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    assertThat(r.json().size()).isEqualTo(2);
    assertThat(r.json().get(0).get("name").asString()).endsWith("№1");
    assertThat(r.json().get(1).get("name").asString()).endsWith("№2");
    assertThat(r.json().get(1).get("teacher").asString()).isEqualTo("Петров А. В.");
    long second = r.json().get(1).get("id").asLong();
    // Второй подгруппе — своё: «не мой предмет» для первой скрывает её у студента.
    assertThat(student.api().put("/api/subjects/" + s + "/mine", Map.of("value", false)).status())
        .isEqualTo(200);
    assertThat(student.api().get("/api/subjects/" + second).status()).isEqualTo(200);
    assertThat(headman.api().post("/api/subjects/" + s + "/subgroups", Map.of("count", 9)).status())
        .isEqualTo(400);

    // Подписи подгрупп – после номера: расписание из файла по-прежнему находит «№2».
    long t = subject(g, "Физкультура " + uniq());
    var named =
        headman
            .api()
            .post(
                "/api/subjects/" + t + "/subgroups",
                Map.of("count", 2, "names", List.of("Волейбол", "")));
    assertThat(named.status()).as(named.body()).isEqualTo(200);
    assertThat(named.json().get(0).get("name").asString()).endsWith("№1 Волейбол");
    assertThat(named.json().get(1).get("name").asString()).endsWith("№2");
    long u = subject(g, "Химия " + uniq());
    assertThat(
            headman
                .api()
                .post(
                    "/api/subjects/" + u + "/subgroups",
                    Map.of("count", 2, "names", List.of("Группа 305", "")))
                .status())
        .isEqualTo(400);
  }

  @Test
  void deleteRemovesSubjectWithItsContent() {
    long g = newGroup("Удаление");
    TestUser headman = newUser(g, "headman");
    TestUser student = newUser(g, "student");
    long s = subject(g, "Лишний предмет " + uniq());
    long hw =
        headman
            .api()
            .post(
                "/api/homework",
                Map.of("subjectId", s, "title", "Удалится", "dueAt", clock.millis() + 86_400_000L))
            .json()
            .get("id")
            .asLong();
    headman
        .api()
        .post("/api/subjects/" + s + "/materials", Map.of("kind", "note", "description", "Текст"));
    assertThat(student.api().delete("/api/subjects/" + s).status()).isEqualTo(403);
    var r = headman.api().delete("/api/subjects/" + s);
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    assertThat(student.api().get("/api/subjects/" + s).status()).isEqualTo(404);
    assertThat(student.api().get("/api/homework/" + hw).status()).isEqualTo(404);
  }
}
