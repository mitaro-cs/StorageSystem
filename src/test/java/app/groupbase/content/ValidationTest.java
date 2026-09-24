package app.groupbase.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.groupbase.web.ApiException;
import org.junit.jupiter.api.Test;

/** Проверки входных данных предметов и заданий без сервера. */
class ValidationTest {

  @Test
  void subjectIconKeys() {
    assertThat(SubjectService.icon(null)).as("не менять").isNull();
    assertThat(SubjectService.icon("")).as("подобрать по названию").isEmpty();
    assertThat(SubjectService.icon(" Atom ")).isEqualTo("atom");
    assertThat(SubjectService.icon("chart-spline")).isEqualTo("chart-spline");
    for (String bad : new String[] {"../etc", "<svg>", "a b", "x".repeat(40), "атом"}) {
      assertThatThrownBy(() -> SubjectService.icon(bad))
          .as(bad)
          .isInstanceOf(ApiException.class)
          .hasMessage("Неизвестная иконка");
    }
  }

  @Test
  void homeworkDifficulty() {
    assertThat(HomeworkService.difficulty(null)).isNull();
    assertThat(HomeworkService.difficulty(0)).as("0 — убрать").isNull();
    assertThat(HomeworkService.difficulty(1)).isEqualTo(1);
    assertThat(HomeworkService.difficulty(3)).isEqualTo(3);
    assertThatThrownBy(() -> HomeworkService.difficulty(4)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> HomeworkService.difficulty(-1)).isInstanceOf(ApiException.class);
  }
}
