package app.groupbase.quiz;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.IntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Тесты онлайн: староста создаёт, студент проходит, сервер проверяет и не выдаёт ответы заранее.
 */
class QuizIT extends IntegrationTest {

  private static Map<String, Object> quiz(boolean published, Integer limit) {
    return Map.of(
        "title",
        "Пределы",
        "description",
        "Проверка по лекции 3",
        "published",
        published,
        "attempts",
        1,
        "showAnswers",
        true,
        "timeLimit",
        limit == null ? 0 : limit,
        "questions",
        List.of(
            Map.of(
                "kind",
                "single",
                "text",
                "2 + 2",
                "options",
                List.of("3", "4"),
                "correct",
                List.of(1)),
            Map.of(
                "kind",
                "multi",
                "text",
                "Чётные",
                "options",
                List.of("1", "2", "4"),
                "correct",
                List.of(1, 2),
                "points",
                2),
            Map.of("kind", "text", "text", "Столица РФ", "accepted", List.of("Москва"))));
  }

  @Test
  void headmanCreatesStudentTakesServerGrades() {
    long g = newGroup("Тесты " + uniq());
    TestUser headman = newUser(g, "headman");
    TestUser student = newUser(g, "student");
    long subject =
        headman
            .api()
            .post("/api/groups/" + g + "/subjects", Map.of("name", "Матан " + uniq()))
            .json()
            .get("id")
            .asLong();

    // Студент тест создать не может.
    var denied =
        student.api().post("/api/subjects/" + subject + "/quizzes", withoutLimit(quiz(true, null)));
    assertThat(denied.status()).isEqualTo(403);

    // Черновик студент не видит.
    var draft =
        headman
            .api()
            .post("/api/subjects/" + subject + "/quizzes", withoutLimit(quiz(false, null)));
    assertThat(draft.status()).as(draft.body()).isEqualTo(200);
    long draftId = draft.json().get("quiz").get("id").asLong();
    assertThat(student.api().get("/api/subjects/" + subject + "/quizzes").json().size()).isZero();
    assertThat(student.api().get("/api/quizzes/" + draftId).status()).isEqualTo(404);

    var made = headman.api().post("/api/subjects/" + subject + "/quizzes", quiz(true, 10));
    assertThat(made.status()).as(made.body()).isEqualTo(200);
    long id = made.json().get("quiz").get("id").asLong();

    // Студенту приходят вопросы без верных ответов.
    var seen = student.api().get("/api/quizzes/" + id);
    assertThat(seen.status()).isEqualTo(200);
    assertThat(seen.json().get("questions").isNull()).isTrue();
    assertThat(seen.body()).doesNotContain("correct\":[").doesNotContain("Москва");

    var start = student.api().post("/api/quizzes/" + id + "/attempts", Map.of());
    assertThat(start.status()).as(start.body()).isEqualTo(200);
    long attempt = start.json().get("id").asLong();
    assertThat(start.json().get("deadline").asLong()).isPositive();
    // Повторный «начать» возвращает ту же попытку.
    assertThat(
            student
                .api()
                .post("/api/quizzes/" + id + "/attempts", Map.of())
                .json()
                .get("id")
                .asLong())
        .isEqualTo(attempt);

    var done =
        student
            .api()
            .post(
                "/api/quiz-attempts/" + attempt + "/finish",
                Map.of(
                    "answers",
                    List.of(
                        Map.of("choices", List.of(1)),
                        Map.of("choices", List.of(2)),
                        Map.of("text", "  москва "))));
    assertThat(done.status()).as(done.body()).isEqualTo(200);
    assertThat(done.json().get("score").asDouble()).isEqualTo(2.0);
    assertThat(done.json().get("max").asDouble()).isEqualTo(4.0);
    assertThat(done.json().get("marks").get(1).get("right").asBoolean()).isFalse();

    // Одна попытка – вторую не начать.
    var again = student.api().post("/api/quizzes/" + id + "/attempts", Map.of());
    assertThat(again.status()).isEqualTo(409);

    // Староста видит результаты.
    var results = headman.api().get("/api/quizzes/" + id + "/results");
    assertThat(results.status()).isEqualTo(200);
    assertThat(results.json().size()).isEqualTo(1);
    assertThat(results.json().get(0).get("score").asDouble()).isEqualTo(2.0);
  }

  @Test
  void lateAnswersAfterTimeLimitDoNotCount() {
    long g = newGroup("Тесты " + uniq());
    TestUser headman = newUser(g, "headman");
    TestUser student = newUser(g, "student");
    long subject =
        headman
            .api()
            .post("/api/groups/" + g + "/subjects", Map.of("name", "Физика " + uniq()))
            .json()
            .get("id")
            .asLong();
    long id =
        headman
            .api()
            .post("/api/subjects/" + subject + "/quizzes", quiz(true, 1))
            .json()
            .get("quiz")
            .get("id")
            .asLong();
    long attempt =
        student.api().post("/api/quizzes/" + id + "/attempts", Map.of()).json().get("id").asLong();
    clock.advance(Duration.ofMinutes(5));
    var done =
        student
            .api()
            .post(
                "/api/quiz-attempts/" + attempt + "/finish",
                Map.of("answers", List.of(Map.of("choices", List.of(1)))));
    assertThat(done.status()).isEqualTo(200);
    assertThat(done.json().get("score").asDouble()).isZero();
  }

  @Test
  void gradingIgnoresCaseSpacesAndYo() {
    var q = new QuizService.Question("text", "?", List.of(), List.of(), List.of("Ёлка"), null);
    assertThat(QuizService.mark(q, new QuizService.Answer(null, "  ЕЛКА ")).right()).isTrue();
    assertThat(QuizService.mark(q, new QuizService.Answer(null, "ель")).right()).isFalse();
  }

  private static Map<String, Object> withoutLimit(Map<String, Object> m) {
    var copy = new java.util.HashMap<>(m);
    copy.remove("timeLimit");
    return copy;
  }
}
