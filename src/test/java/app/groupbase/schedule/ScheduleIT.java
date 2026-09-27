package app.groupbase.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ScheduleIT extends IntegrationTest {

  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
  private static final long DAY = Duration.ofDays(1).toMillis();

  private static String at(Instant t) {
    return UTC.format(t);
  }

  /**
   * Расписание от дня {@code day}: лекции по матанализу раз в неделю ({@code lectures} штук),
   * практика, физкультура дважды и праздник на весь день (его не загружаем).
   */
  private static String ics(Instant day, int lectures) {
    return String.join(
        "\r\n",
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        "PRODID:-//Расписание вуза//RU",
        "BEGIN:VEVENT",
        "UID:lec@vuz",
        "DTSTART:" + at(day.plus(Duration.ofMinutes(390))),
        "DTEND:" + at(day.plus(Duration.ofMinutes(485))),
        "RRULE:FREQ=WEEKLY;COUNT=" + lectures,
        "SUMMARY:Математический анализ (Лекция)",
        "LOCATION:214",
        "END:VEVENT",
        "BEGIN:VEVENT",
        "UID:pr@vuz",
        "DTSTART:" + at(day.plus(Duration.ofDays(2)).plus(Duration.ofHours(8))),
        "DTEND:" + at(day.plus(Duration.ofDays(2)).plus(Duration.ofMinutes(575))),
        "SUMMARY:Мат. анализ\\, пр.",
        "DESCRIPTION:Преподаватель: Иванов И. И.",
        "END:VEVENT",
        "BEGIN:VEVENT",
        "UID:pe@vuz",
        "DTSTART:" + at(day.plus(Duration.ofDays(3)).plus(Duration.ofHours(10))),
        "RRULE:FREQ=WEEKLY;COUNT=2",
        "SUMMARY:Физкультура",
        "END:VEVENT",
        "BEGIN:VEVENT",
        "UID:holiday@vuz",
        "DTSTART;VALUE=DATE:" + DATE.format(day.plus(Duration.ofDays(5))),
        "SUMMARY:Праздник",
        "END:VEVENT",
        "END:VCALENDAR");
  }

  private long subject(long group, String name) {
    return admin()
        .post("/api/groups/" + group + "/subjects", Map.of("name", name))
        .json()
        .get("id")
        .asLong();
  }

  private static JsonNode title(JsonNode preview, String key) {
    for (JsonNode t : preview.get("titles")) {
      if (t.get("key").asString().equals(key)) {
        return t;
      }
    }
    throw new AssertionError("нет названия " + key + " в " + preview);
  }

  @Test
  void headmanLoadsCalendarFileStudentsSeeLessonsAndAddHomework() {
    long g = newGroup("Расписание");
    TestUser headman = newUser(g, "headman");
    TestUser student = newUser(g, "student");
    long math = subject(g, "Математический анализ");
    Instant day = clock.instant().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.DAYS);
    String file = ics(day, 4);
    String base = "/api/groups/" + g + "/schedule";

    // Вести расписание студенту по умолчанию нельзя (⚙ выключено).
    assertThat(student.api().post(base + "/preview", Map.of("ics", file)).status()).isEqualTo(403);

    var preview = headman.api().post(base + "/preview", Map.of("ics", file));
    assertThat(preview.status()).as(preview.body()).isEqualTo(200);
    JsonNode p = preview.json();
    assertThat(p.get("lessons").asInt()).isEqualTo(7);
    assertThat(p.get("allDay").asInt()).isEqualTo(1);
    assertThat(p.get("existing").asInt()).isZero();
    JsonNode lectures = title(p, "математический анализ");
    assertThat(lectures.get("count").asInt()).isEqualTo(4);
    assertThat(lectures.get("subjectId").asLong()).isEqualTo(math);
    assertThat(lectures.get("kinds").get("lecture").asInt()).isEqualTo(4);
    assertThat(lectures.get("places").get(0).asString()).isEqualTo("214");
    // «Мат. анализ, пр.» — тот же предмет, практика, преподаватель из описания.
    JsonNode practice = title(p, "мат анализ");
    assertThat(practice.get("subjectId").asLong()).isEqualTo(math);
    assertThat(practice.get("teacher").asString()).isEqualTo("Иванов И. И.");
    assertThat(title(p, "физкультура").get("subjectId").isNull()).isTrue();

    // Физкультуры среди предметов нет — староста создаёт её прямо при загрузке.
    List<Map<String, Object>> choices = List.of(Map.of("key", "физкультура", "action", "create"));
    var imported =
        headman
            .api()
            .post(base + "/import", Map.of("ics", file, "choices", choices, "replace", true));
    assertThat(imported.status()).as(imported.body()).isEqualTo(200);
    assertThat(imported.json().get("created").asInt()).isEqualTo(7);
    assertThat(imported.json().get("subjects").asInt()).isEqualTo(1);

    // Тот же файл ещё раз — ничего не дублируется и не создаётся заново.
    var again =
        headman
            .api()
            .post(base + "/import", Map.of("ics", file, "choices", choices, "replace", true));
    assertThat(again.json().get("created").asInt()).isZero();
    assertThat(again.json().get("updated").asInt()).isZero();
    assertThat(again.json().get("subjects").asInt()).isZero();

    long from = day.toEpochMilli() - DAY;
    String range = "&from=" + from + "&to=" + (from + 40 * DAY);
    var list = student.api().get("/api/schedule?group=" + g + range);
    assertThat(list.status()).as(list.body()).isEqualTo(200);
    assertThat(list.json().size()).isEqualTo(7);
    JsonNode first = list.json().get(0);
    assertThat(first.get("subject").get("id").asLong()).isEqualTo(math);
    assertThat(first.get("kind").asString()).isEqualTo("lecture");
    assertThat(first.get("place").asString()).isEqualTo("214");
    assertThat(first.get("can").get("edit").asBoolean()).isFalse();
    long lesson = first.get("id").asLong();
    long lessonStart = first.get("startsAt").asLong();

    // «Сегодня» показывает пары сегодня и завтра.
    var today = student.api().get("/api/today?group=" + g);
    assertThat(today.json().get("lessons").size()).isGreaterThanOrEqualTo(1);
    assertThat(student.api().get("/api/subjects/" + math).json().get("lessons").asInt())
        .isEqualTo(5);

    // Студент сам добавляет задание к паре — оно на странице пары.
    var hw =
        student
            .api()
            .post(
                "/api/homework",
                Map.of(
                    "subjectId",
                    math,
                    "title",
                    "Решить №5",
                    "dueAt",
                    lessonStart,
                    "lessonId",
                    lesson));
    assertThat(hw.status()).as(hw.body()).isEqualTo(200);
    assertThat(hw.json().get("lesson").get("id").asLong()).isEqualTo(lesson);
    var detail = student.api().get("/api/lessons/" + lesson);
    assertThat(detail.status()).as(detail.body()).isEqualTo(200);
    assertThat(detail.json().get("homework").size()).isEqualTo(1);
    assertThat(detail.json().get("lesson").get("homework").asInt()).isEqualTo(1);
    assertThat(detail.json().get("prev").isNull()).isTrue();
    // Следующая пара того же предмета — практика по матанализу.
    assertThat(detail.json().get("next").get("kind").asString()).isEqualTo("practice");

    // Пара другого предмета к заданию по матанализу не подходит.
    long pe = -1;
    for (JsonNode l : list.json()) {
      if (l.get("subject").get("id").asLong() != math) {
        pe = l.get("id").asLong();
      }
    }
    assertThat(
            student
                .api()
                .post(
                    "/api/homework",
                    Map.of("subjectId", math, "title", "x", "dueAt", lessonStart, "lessonId", pe))
                .status())
        .isEqualTo(400);

    // Тему пары меняет староста, не студент; повторная загрузка тему не стирает.
    Map<String, Object> note = Map.of("note", "Лекция 1. Пределы");
    assertThat(student.api().patch("/api/lessons/" + lesson, note).status()).isEqualTo(403);
    assertThat(headman.api().patch("/api/lessons/" + lesson, note).status()).isEqualTo(200);

    // Обновлённый файл: последней лекции нет — она уходит, остальное (с темой и заданием) — нет.
    var shorter = headman.api().post(base + "/preview", Map.of("ics", ics(day, 3)));
    assertThat(shorter.json().get("replaced").asInt()).isEqualTo(1);
    var replaced =
        headman.api().post(base + "/import", Map.of("ics", ics(day, 3), "choices", choices));
    assertThat(replaced.json().get("deleted").asInt()).isEqualTo(1);
    assertThat(student.api().get("/api/schedule?group=" + g + range).json().size()).isEqualTo(6);
    var kept = student.api().get("/api/lessons/" + lesson).json();
    assertThat(kept.get("lesson").get("note").asString()).isEqualTo("Лекция 1. Пределы");
    assertThat(kept.get("homework").size()).isEqualTo(1);

    // Офлайн-копия получает пары.
    var sync = student.api().get("/api/sync?after=0");
    assertThat(sync.json().get("lessons").get("upsert").size()).isEqualTo(6);
  }

  @Test
  void lessonsByHandRepeatPermissionsAndOtherGroups() {
    long g = newGroup("Пары вручную");
    TestUser headman = newUser(g, "headman");
    TestUser student = newUser(g, "student");
    TestUser stranger = newUser(newGroup("Чужие"), "student");
    long physics = subject(g, "Физика");
    long start = clock.millis() + 3 * DAY;
    Map<String, Object> in =
        Map.of(
            "subjectId",
            physics,
            "kind",
            "lab",
            "startsAt",
            start,
            "endsAt",
            start + 95 * 60_000,
            "place",
            "5-301",
            "repeatWeeks",
            2);

    assertThat(student.api().post("/api/groups/" + g + "/lessons", in).status()).isEqualTo(403);
    var made = headman.api().post("/api/groups/" + g + "/lessons", in);
    assertThat(made.status()).as(made.body()).isEqualTo(200);
    assertThat(made.json().size()).isEqualTo(3);
    assertThat(made.json().get(0).get("title").asString()).isEqualTo("Физика");
    assertThat(made.json().get(2).get("startsAt").asLong() - start).isEqualTo(14 * DAY);
    long id = made.json().get(0).get("id").asLong();

    // Чужая группа пару не видит; неверное время — понятная ошибка.
    assertThat(stranger.api().get("/api/lessons/" + id).status()).isEqualTo(404);
    var bad =
        headman
            .api()
            .post(
                "/api/groups/" + g + "/lessons",
                Map.of("title", "Пара", "startsAt", start, "endsAt", start - 1));
    assertThat(bad.status()).isEqualTo(400);
    assertThat(bad.json().get("message").asString()).contains("позже начала");

    // Староста разрешил студентам вести расписание.
    headman
        .api()
        .put(
            "/api/groups/" + g + "/permissions",
            Map.of("role", "student", "permission", "manage_schedule", "allowed", true));
    var byStudent =
        student
            .api()
            .post(
                "/api/groups/" + g + "/lessons",
                Map.of("title", "Классный час", "startsAt", start, "endsAt", start + 3_600_000));
    assertThat(byStudent.status()).as(byStudent.body()).isEqualTo(200);

    // Материал к паре; пару удалили — материал остался, просто без пары.
    long fileId =
        student
            .api()
            .upload("Методичка.pdf", "%PDF-1.4\n%%EOF\n".getBytes())
            .json()
            .get("id")
            .asLong();
    var m =
        student
            .api()
            .post(
                "/api/subjects/" + physics + "/materials",
                Map.of("kind", "file", "fileId", fileId, "lessonId", id));
    assertThat(m.status()).as(m.body()).isEqualTo(200);
    assertThat(student.api().get("/api/lessons/" + id).json().get("materials").size()).isEqualTo(1);
    assertThat(headman.api().delete("/api/lessons/" + id).status()).isEqualTo(200);
    var material = student.api().get("/api/materials/" + m.json().get("id").asLong()).json();
    assertThat(material.get("lessonId").isNull()).isTrue();

    var cleared = headman.api().delete("/api/groups/" + g + "/schedule");
    assertThat(cleared.json().get("deleted").asInt()).isEqualTo(3);
  }

  @Test
  void notMySubjectLeavesTheGeneralSchedule() {
    long g = newGroup("Подгруппы");
    TestUser headman = newUser(g, "headman");
    TestUser student = newUser(g, "student");
    long en1 = subject(g, "Английский язык №1");
    long en2 = subject(g, "Английский язык №2");
    long start = clock.millis() + DAY;
    for (long s : List.of(en1, en2)) {
      headman
          .api()
          .post(
              "/api/groups/" + g + "/lessons",
              Map.of("subjectId", s, "startsAt", start, "endsAt", start + 5_400_000));
    }
    student.api().put("/api/subjects/" + en2 + "/mine", Map.of("value", false));
    String range = "&from=" + (start - DAY) + "&to=" + (start + DAY);
    var mine = student.api().get("/api/schedule?group=" + g + range).json();
    assertThat(mine.size()).isEqualTo(1);
    assertThat(mine.get(0).get("subject").get("id").asLong()).isEqualTo(en1);
    // На странице предмета — все его пары.
    assertThat(student.api().get("/api/schedule?subject=" + en2 + range).json().size())
        .isEqualTo(1);
  }

  @Test
  void explainsBadFiles() {
    long g = newGroup("Плохие файлы");
    TestUser headman = newUser(g, "headman");
    String base = "/api/groups/" + g + "/schedule/preview";
    var notIcs = headman.api().post(base, Map.of("ics", "%PDF-1.4 это не календарь"));
    assertThat(notIcs.status()).isEqualTo(400);
    assertThat(notIcs.json().get("message").asString()).contains(".ics");
    var empty =
        headman.api().post(base, Map.of("ics", "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR"));
    assertThat(empty.status()).isEqualTo(400);
    assertThat(empty.json().get("message").asString()).contains("нет пар");
    assertThat(headman.api().get("/api/schedule?group=" + g).status()).isEqualTo(400);
  }
}
