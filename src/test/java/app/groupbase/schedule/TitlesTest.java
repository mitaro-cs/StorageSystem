package app.groupbase.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TitlesTest {

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "Математический анализ (Лекция)|Математический анализ|LECTURE",
        "Лек. Физика|Физика|LECTURE",
        "Лекционное занятие: История России|История России|LECTURE",
        "[пр] Иностранный язык|Иностранный язык|PRACTICE",
        "Практическое занятие – Программирование|Программирование|PRACTICE",
        "Физика, лаб.|Физика|LAB",
        "Лабораторная работа Информатика|Информатика|LAB",
        "Семинар по философии|По философии|SEMINAR",
        "Консультация перед экзаменом по физике|Перед экзаменом по физике|CONSULT",
        "Экзамен: Линейная алгебра|Линейная алгебра|EXAM",
        "Дифференцированный зачёт Экономика|Экономика|CREDIT",
        "Лексикология английского языка|Лексикология английского языка|OTHER",
        "Классный час|Классный час|OTHER",
        "Лекция|Лекция|LECTURE"
      })
  void kindAndName(String summary, String name, LessonKind kind) {
    Titles.Parsed p = Titles.parse(summary, "", "", "");
    assertThat(p.name()).isEqualTo(name);
    assertThat(p.kind()).isEqualTo(kind);
  }

  @Test
  void teacherAndPlaceFromTitleOrDescription() {
    Titles.Parsed a = Titles.parse("Физика, ауд. 214, Иванов И.И.", "", "", "");
    assertThat(a.name()).isEqualTo("Физика");
    assertThat(a.place()).isEqualTo("ауд. 214");
    assertThat(a.teacher()).isEqualTo("Иванов И. И.");

    Titles.Parsed b =
        Titles.parse(
            "Химия", "Группа: БИН2509\nПреподаватель: Петрова А. С.\nАудитория: 5-301", "", "");
    assertThat(b.teacher()).isEqualTo("Петрова А. С.");
    assertThat(b.place()).isEqualTo("5-301");

    // Место из LOCATION важнее, вид — из категорий, если в названии его нет.
    Titles.Parsed c = Titles.parse("Химия", "", "Лаб. корпус, 12", "Лабораторная работа");
    assertThat(c.place()).isEqualTo("Лаб. корпус, 12");
    assertThat(c.kind()).isEqualTo(LessonKind.LAB);
  }

  @Test
  void keyGroupsSameSubject() {
    assertThat(Titles.key("Математический  анализ.")).isEqualTo("математический анализ");
    assertThat(Titles.key("Английский язык № 1")).isEqualTo(Titles.key("английский ЯЗЫК №1"));
    assertThat(Titles.key("Ёмкость")).isEqualTo("емкость");
  }

  @Test
  void matchesGroupSubjects() {
    List<Titles.Subject> group =
        List.of(
            new Titles.Subject(1, "Математический анализ"),
            new Titles.Subject(2, "Физическая культура"),
            new Titles.Subject(3, "Основы программирования"),
            new Titles.Subject(4, "Английский язык №1 Сильная группа"),
            new Titles.Subject(5, "Английский язык №2 Слабая группа"),
            new Titles.Subject(6, "Физика"));
    assertThat(Titles.match("Математический анализ", group)).isEqualTo(1);
    assertThat(Titles.match("Мат. анализ", group)).isEqualTo(1);
    assertThat(Titles.match("Физика", group)).isEqualTo(6);
    assertThat(Titles.match("Физическая культура и спорт", group)).isEqualTo(2);
    // Сокращения из расписаний: «Физкультура», «Физ-ра».
    assertThat(Titles.match("Физкультура", group)).isEqualTo(2);
    assertThat(Titles.match("Физ-ра", group)).isEqualTo(2);
    assertThat(Titles.match("ОП", group)).isEqualTo(3);
    assertThat(Titles.match("Основы программирования", group)).isEqualTo(3);
    assertThat(Titles.match("Английский язык 2 подгруппа", group)).isEqualTo(5);
    // Без номера подгруппы — два английских одинаково похожи: не угадываем.
    assertThat(Titles.match("Английский язык", group)).isNull();
    assertThat(Titles.match("Классный час", group)).isNull();
  }

  @Test
  void wordsWithOtherEndings() {
    assertThat(Titles.similar("программирование", "программирования")).isTrue();
    assertThat(Titles.similar("мат", "математический")).isTrue();
    assertThat(Titles.similar("физика", "физическая")).isFalse();
    assertThat(Titles.similar("ма", "математика")).isFalse();
  }

  @Test
  void tidyRemovesLeftovers() {
    assertThat(Titles.tidy("( ) Физика , ")).isEqualTo("Физика");
    assertThat(Titles.tidy("– Химия |")).isEqualTo("Химия");
    assertThat(Titles.tidy("Физика (")).isEqualTo("Физика");
  }
}
