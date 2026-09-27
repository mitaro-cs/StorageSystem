package app.groupbase.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class IcsTest {

  private static final ZoneId MSK = ZoneId.of("Europe/Moscow");

  private static String cal(String... events) {
    return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" + String.join("\r\n", events) + "\r\nEND:VCALENDAR";
  }

  private static long msk(int y, int mo, int d, int h, int mi) {
    return LocalDateTime.of(y, mo, d, h, mi).atZone(MSK).toInstant().toEpochMilli();
  }

  @Test
  void singleEventWithZoneEscapesAndFoldedLines() {
    var r =
        Ics.parse(
            cal(
                "BEGIN:VEVENT",
                "UID:a1@vuz",
                "DTSTART;TZID=Europe/Moscow:20260901T093000",
                "DTEND;TZID=Europe/Moscow:20260901T110500",
                "SUMMARY:Математический анализ\\, лекция",
                "LOCATION:ауд. 214",
                "DESCRIPTION:Преподаватель: Иванов И. И.\\nПринести тетрадь и ",
                " калькулятор",
                "END:VEVENT"),
            MSK,
            0);
    assertThat(r.events()).hasSize(1);
    Ics.Event e = r.events().getFirst();
    assertThat(e.summary()).isEqualTo("Математический анализ, лекция");
    assertThat(e.location()).isEqualTo("ауд. 214");
    assertThat(e.description())
        .isEqualTo("Преподаватель: Иванов И. И.\nПринести тетрадь и калькулятор");
    assertThat(e.start()).isEqualTo(msk(2026, 9, 1, 9, 30));
    assertThat(e.end()).isEqualTo(msk(2026, 9, 1, 11, 5));
    assertThat(e.source()).isEqualTo("a1@vuz");
  }

  @Test
  void utcFloatingWindowsZoneAndDuration() {
    var r =
        Ics.parse(
            cal(
                "BEGIN:VEVENT",
                "UID:u",
                "DTSTART:20260902T063000Z",
                "DURATION:PT1H35M",
                "SUMMARY:В UTC",
                "END:VEVENT",
                "BEGIN:VEVENT",
                "UID:f",
                "DTSTART:20260902T093000",
                "SUMMARY:Плавающее — по поясу календаря",
                "END:VEVENT",
                "BEGIN:VEVENT",
                "UID:w",
                "DTSTART;TZID=\"Russian Standard Time\":20260902T120000",
                "DTEND;TZID=\"Russian Standard Time\":20260902T133500",
                "SUMMARY:Outlook",
                "END:VEVENT"),
            ZoneId.of("Asia/Yekaterinburg"),
            0);
    var byUid = r.events().stream().collect(Collectors.toMap(Ics.Event::source, x -> x));
    assertThat(byUid.get("u").start()).isEqualTo(msk(2026, 9, 2, 9, 30));
    assertThat(byUid.get("u").end() - byUid.get("u").start())
        .isEqualTo(Duration.ofMinutes(95).toMillis());
    // Без пояса — пояс сайта (здесь Екатеринбург), без конца — полтора часа.
    long ekb =
        LocalDateTime.of(2026, 9, 2, 9, 30)
            .atZone(ZoneId.of("Asia/Yekaterinburg"))
            .toInstant()
            .toEpochMilli();
    assertThat(byUid.get("f").start()).isEqualTo(ekb);
    assertThat(byUid.get("f").end() - ekb).isEqualTo(Ics.DEFAULT_LENGTH.toMillis());
    assertThat(byUid.get("w").start()).isEqualTo(msk(2026, 9, 2, 12, 0));
  }

  @Test
  void calendarZoneAndMozillaStylePath() {
    // Пояс сайта здесь — UTC: время без пояса берётся из X-WR-TIMEZONE календаря.
    var r =
        Ics.parse(
            String.join(
                "\r\n",
                "BEGIN:VCALENDAR",
                "X-WR-TIMEZONE:Europe/Moscow",
                "BEGIN:VEVENT",
                "UID:m",
                "DTSTART;TZID=/mozilla.org/20050126_1/Europe/Moscow:20260903T090000",
                "SUMMARY:Физика",
                "END:VEVENT",
                "BEGIN:VEVENT",
                "UID:x",
                "DTSTART:20260903T110000",
                "SUMMARY:Химия",
                "END:VEVENT",
                "END:VCALENDAR"),
            ZoneId.of("UTC"),
            0);
    assertThat(r.events())
        .extracting(Ics.Event::start)
        .containsExactly(msk(2026, 9, 3, 9, 0), msk(2026, 9, 3, 11, 0));
  }

  @Test
  void weeklyEveryOtherWeekWithExdateAndMovedOccurrence() {
    var r =
        Ics.parse(
            cal(
                "BEGIN:VEVENT",
                "UID:series",
                "DTSTART;TZID=Europe/Moscow:20260901T093000",
                "DTEND;TZID=Europe/Moscow:20260901T110500",
                "RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=TU,TH;UNTIL=20261031T205959Z",
                "EXDATE;TZID=Europe/Moscow:20260915T093000",
                "SUMMARY:Физика (пр.)",
                "BEGIN:VALARM",
                "TRIGGER:-PT15M",
                "DESCRIPTION:Напоминание",
                "END:VALARM",
                "END:VEVENT",
                // Перенос: занятие 17.09 в 9:30 перенесли на 18.09 в 13:00.
                "BEGIN:VEVENT",
                "UID:series",
                "RECURRENCE-ID;TZID=Europe/Moscow:20260917T093000",
                "DTSTART;TZID=Europe/Moscow:20260918T130000",
                "DTEND;TZID=Europe/Moscow:20260918T143500",
                "SUMMARY:Физика (пр.) — перенос",
                "END:VEVENT",
                // Отмена: 01.10 не будет.
                "BEGIN:VEVENT",
                "UID:series",
                "RECURRENCE-ID;TZID=Europe/Moscow:20261001T093000",
                "DTSTART;TZID=Europe/Moscow:20261001T093000",
                "STATUS:CANCELLED",
                "SUMMARY:Физика (пр.)",
                "END:VEVENT"),
            MSK,
            0);
    // Недели через одну (1, 15, 29 сентября, 13, 27 октября — вт и чт), 15.09 исключено,
    // 17.09 перенесено на 18.09, 01.10 отменено.
    assertThat(r.events())
        .extracting(Ics.Event::start)
        .containsExactly(
            msk(2026, 9, 1, 9, 30),
            msk(2026, 9, 3, 9, 30),
            msk(2026, 9, 18, 13, 0),
            msk(2026, 9, 29, 9, 30),
            msk(2026, 10, 13, 9, 30),
            msk(2026, 10, 15, 9, 30),
            msk(2026, 10, 27, 9, 30),
            msk(2026, 10, 29, 9, 30));
    assertThat(r.cancelled()).isEqualTo(1);
    // Повторная загрузка узнаёт занятия серии по UID и исходному началу, перенос — тоже.
    assertThat(r.events().get(2).source()).isEqualTo("series#" + msk(2026, 9, 17, 9, 30));
    assertThat(r.events().get(2).summary()).isEqualTo("Физика (пр.) — перенос");
    assertThat(r.events().stream().map(Ics.Event::source).distinct()).hasSize(8);
    assertThat(r.events().stream().filter(e -> e.description().contains("Напоминание"))).isEmpty();
  }

  @Test
  void countDailyMonthlyAndRdate() {
    var r =
        Ics.parse(
            cal(
                "BEGIN:VEVENT",
                "UID:c",
                "DTSTART;TZID=Europe/Moscow:20260907T090000",
                "RRULE:FREQ=WEEKLY;COUNT=3",
                "RDATE;TZID=Europe/Moscow:20260930T150000",
                "SUMMARY:Английский",
                "END:VEVENT",
                "BEGIN:VEVENT",
                "UID:d",
                "DTSTART;TZID=Europe/Moscow:20260907T180000",
                "RRULE:FREQ=DAILY;BYDAY=MO,WE;UNTIL=20260911",
                "SUMMARY:Консультации",
                "END:VEVENT",
                "BEGIN:VEVENT",
                "UID:m",
                "DTSTART;TZID=Europe/Moscow:20260915T100000",
                "RRULE:FREQ=MONTHLY;COUNT=2",
                "SUMMARY:Классный час",
                "END:VEVENT"),
            MSK,
            0);
    assertThat(r.events().stream().filter(e -> e.summary().equals("Английский")))
        .extracting(Ics.Event::start)
        .containsExactly(
            msk(2026, 9, 7, 9, 0),
            msk(2026, 9, 14, 9, 0),
            msk(2026, 9, 21, 9, 0),
            msk(2026, 9, 30, 15, 0));
    assertThat(r.events().stream().filter(e -> e.summary().equals("Консультации")))
        .extracting(Ics.Event::start)
        .containsExactly(msk(2026, 9, 7, 18, 0), msk(2026, 9, 9, 18, 0));
    assertThat(r.events().stream().filter(e -> e.summary().equals("Классный час")))
        .extracting(Ics.Event::start)
        .containsExactly(msk(2026, 9, 15, 10, 0), msk(2026, 10, 15, 10, 0));
  }

  @Test
  void allDayPastUnsupportedAndDuplicateUids() {
    long notBefore = msk(2026, 9, 1, 0, 0);
    var r =
        Ics.parse(
            cal(
                "BEGIN:VEVENT",
                "UID:holiday",
                "DTSTART;VALUE=DATE:20261104",
                "SUMMARY:День народного единства",
                "END:VEVENT",
                "BEGIN:VEVENT",
                "UID:old",
                "DTSTART;TZID=Europe/Moscow:20260310T090000",
                "SUMMARY:Прошлый семестр",
                "END:VEVENT",
                "BEGIN:VEVENT",
                "UID:weird",
                "DTSTART;TZID=Europe/Moscow:20260910T090000",
                "RRULE:FREQ=MONTHLY;BYDAY=1TH",
                "SUMMARY:Первый четверг",
                "END:VEVENT",
                "BEGIN:VEVENT",
                "UID:same",
                "DTSTART;TZID=Europe/Moscow:20260911T090000",
                "SUMMARY:Одинаковый UID 1",
                "END:VEVENT",
                "BEGIN:VEVENT",
                "UID:same",
                "DTSTART;TZID=Europe/Moscow:20260911T110000",
                "SUMMARY:Одинаковый UID 2",
                "END:VEVENT"),
            MSK,
            notBefore);
    assertThat(r.allDay()).isEqualTo(1);
    assertThat(r.past()).isEqualTo(1);
    assertThat(r.unsupported()).isEqualTo(1);
    assertThat(r.events())
        .extracting(Ics.Event::summary)
        .containsExactly("Первый четверг", "Одинаковый UID 1", "Одинаковый UID 2");
    assertThat(r.events().stream().map(Ics.Event::source).distinct()).hasSize(3);
  }

  @Test
  void rejectsNotACalendarAndTooMany() {
    assertThatThrownBy(() -> Ics.parse("PDF-1.4 что-то", MSK, 0))
        .isInstanceOf(Ics.Invalid.class)
        .hasMessageContaining(".ics");
    String many =
        cal(
            "BEGIN:VEVENT",
            "UID:daily",
            "DTSTART;TZID=Europe/Moscow:20260901T090000",
            "RRULE:FREQ=DAILY",
            "SUMMARY:Каждый день",
            "END:VEVENT");
    // Одно событие без конца даёт не больше MAX_PER_EVENT занятий — файл не «взрывается».
    assertThat(Ics.parse(many, MSK, 0).events()).hasSize(Ics.MAX_PER_EVENT);
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < 8; i++) {
      b.append("BEGIN:VEVENT\r\nUID:d")
          .append(i)
          .append("\r\nDTSTART;TZID=Europe/Moscow:20260901T0")
          .append(i)
          .append("0000\r\nRRULE:FREQ=DAILY\r\nSUMMARY:x\r\nEND:VEVENT\r\n");
    }
    assertThatThrownBy(() -> Ics.parse(cal(b.toString().strip()), MSK, 0))
        .isInstanceOf(Ics.Invalid.class)
        .hasMessageContaining("семестра");
  }

  @Test
  void helpers() {
    assertThat(Ics.duration("PT1H30M")).isEqualTo(Duration.ofMinutes(90));
    assertThat(Ics.duration("P1W")).isEqualTo(Duration.ofDays(7));
    assertThat(Ics.duration("-PT15M")).isEqualTo(Duration.ofMinutes(-15));
    assertThat(Ics.duration("бред")).isNull();
    assertThat(Ics.unfold("A:1\r\n b\n\tc\r\nB:2")).isEqualTo(List.of("A:1bc", "B:2"));
    assertThat(Ics.unescape("a\\,b\\;c\\\\d\\ne")).isEqualTo("a,b;c\\d\ne");
    assertThat(Ics.zone("(UTC+03:00) Moscow, St. Petersburg", Map.of(), ZoneId.of("UTC")))
        .isEqualTo(ZoneOffset.ofHours(3));
    assertThat(Ics.zone("Непонятный пояс", Map.of(), MSK)).isEqualTo(MSK);
  }
}
