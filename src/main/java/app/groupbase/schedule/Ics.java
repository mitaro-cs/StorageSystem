package app.groupbase.schedule;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Файл календаря (.ics, RFC 5545) → занятия. Своими силами, без библиотек: перенесённые строки,
 * параметры в кавычках, время с TZID (и названиями поясов Windows), в UTC и «плавающее», повторения
 * RRULE (DAILY, WEEKLY, MONTHLY с INTERVAL, UNTIL, COUNT, BYDAY, BYMONTHDAY, BYMONTH), исключения
 * EXDATE, дополнительные даты RDATE, перенос и отмена одного занятия (RECURRENCE-ID,
 * STATUS:CANCELLED). События на весь день (праздники, «сессия») — не пары: их пропускаем.
 */
final class Ics {

  /** Больше занятий в одном файле — это не расписание группы, а чей-то календарь за годы. */
  static final int MAX_EVENTS = 3000;

  /** Одно повторяющееся событие даёт не больше занятий (на всякий случай — от бесконечных). */
  static final int MAX_PER_EVENT = 400;

  /** Нет конца — пара по умолчанию: полтора часа. */
  static final Duration DEFAULT_LENGTH = Duration.ofMinutes(90);

  static final Duration MAX_LENGTH = Duration.ofHours(12);

  /** Повторения без конца разворачиваем не дальше двух лет от начала. */
  static final Duration HORIZON = Duration.ofDays(2 * 366);

  /**
   * @param source ключ события: UID, у повторов — UID и исходное начало; по нему повторная загрузка
   *     того же файла обновляет занятие, а не дублирует
   */
  record Event(
      String source,
      String summary,
      String location,
      String description,
      String categories,
      long start,
      long end) {}

  /**
   * @param allDay пропущено событий на весь день
   * @param cancelled отменённых
   * @param unsupported повторений, которые не разобрать, — взято только первое занятие
   * @param past занятий, закончившихся раньше {@code notBefore}
   */
  record Result(List<Event> events, int allDay, int cancelled, int unsupported, int past) {}

  /** Файл не разобрать — сообщение для человека. */
  static final class Invalid extends RuntimeException {
    Invalid(String message) {
      super(message);
    }
  }

  private record Prop(String name, Map<String, String> params, String value) {
    String param(String key) {
      return params.get(key);
    }
  }

  private static final class Component {
    final List<Prop> props = new ArrayList<>();

    Prop get(String name) {
      for (Prop p : props) {
        if (p.name().equals(name)) {
          return p;
        }
      }
      return null;
    }

    List<Prop> all(String name) {
      return props.stream().filter(p -> p.name().equals(name)).toList();
    }

    String text(String name) {
      Prop p = get(name);
      return p == null ? "" : unescape(p.value()).strip();
    }
  }

  /** Время из файла: местное и пояс; у «весь день» — только дата. */
  private record When(LocalDateTime local, ZoneId zone, boolean allDay) {
    Instant instant() {
      return local.atZone(zone).toInstant();
    }
  }

  private record Rule(
      String freq,
      int interval,
      Integer count,
      Instant until,
      List<DayOfWeek> byDay,
      List<Integer> byMonthDay,
      Set<Integer> byMonth,
      DayOfWeek weekStart) {}

  private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
  private static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss");
  private static final DateTimeFormatter DATE_TIME_SHORT =
      DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmm");

  /** «/mozilla.org/20050126_1/Europe/Moscow» и подобное — берём имя пояса IANA с конца. */
  private static final Pattern IANA =
      Pattern.compile("([A-Za-z]+/[A-Za-z0-9_+-]+(?:/[A-Za-z0-9_+-]+)?)$");

  private static final Pattern GMT_OFFSET =
      Pattern.compile("(?:GMT|UTC)\\s*([+-])\\s*(\\d{1,2})(?::?(\\d{2}))?");

  /** Названия поясов Windows (Outlook): российские и самые частые. */
  private static final Map<String, String> WINDOWS_ZONES =
      Map.ofEntries(
          Map.entry("russian standard time", "Europe/Moscow"),
          Map.entry("kaliningrad standard time", "Europe/Kaliningrad"),
          Map.entry("russia time zone 3", "Europe/Samara"),
          Map.entry("astrakhan standard time", "Europe/Astrakhan"),
          Map.entry("volgograd standard time", "Europe/Volgograd"),
          Map.entry("saratov standard time", "Europe/Saratov"),
          Map.entry("ekaterinburg standard time", "Asia/Yekaterinburg"),
          Map.entry("omsk standard time", "Asia/Omsk"),
          Map.entry("n. central asia standard time", "Asia/Novosibirsk"),
          Map.entry("altai standard time", "Asia/Barnaul"),
          Map.entry("tomsk standard time", "Asia/Tomsk"),
          Map.entry("north asia standard time", "Asia/Krasnoyarsk"),
          Map.entry("north asia east standard time", "Asia/Irkutsk"),
          Map.entry("transbaikal standard time", "Asia/Chita"),
          Map.entry("yakutsk standard time", "Asia/Yakutsk"),
          Map.entry("vladivostok standard time", "Asia/Vladivostok"),
          Map.entry("magadan standard time", "Asia/Magadan"),
          Map.entry("sakhalin standard time", "Asia/Sakhalin"),
          Map.entry("russia time zone 10", "Asia/Srednekolymsk"),
          Map.entry("russia time zone 11", "Asia/Kamchatka"),
          Map.entry("belarus standard time", "Europe/Minsk"),
          Map.entry("utc", "UTC"),
          Map.entry("coordinated universal time", "UTC"),
          Map.entry("gmt standard time", "Europe/London"),
          Map.entry("w. europe standard time", "Europe/Berlin"));

  private Ics() {}

  /**
   * @param fallback пояс для времени без пояса, если в файле нет X-WR-TIMEZONE (пояс сайта)
   * @param notBefore занятия, закончившиеся раньше, пропускаются (прошлые семестры)
   */
  static Result parse(String text, ZoneId fallback, long notBefore) {
    List<String> lines = unfold(text);
    if (lines.stream().noneMatch(l -> l.strip().equalsIgnoreCase("BEGIN:VCALENDAR"))) {
      throw new Invalid("Это не файл календаря: нужен файл .ics (в нём есть BEGIN:VCALENDAR)");
    }
    Deque<String> stack = new ArrayDeque<>();
    List<Component> events = new ArrayList<>();
    Map<String, ZoneOffset> offsets = new HashMap<>();
    Component current = null;
    String tzid = null;
    String calendarZone = null;
    for (String line : lines) {
      Prop p = prop(line);
      if (p == null) {
        continue;
      }
      if (p.name().equals("BEGIN")) {
        String what = p.value().strip().toUpperCase(Locale.ROOT);
        stack.push(what);
        if (what.equals("VEVENT")) {
          current = new Component();
        } else if (what.equals("VTIMEZONE")) {
          tzid = null;
        }
        continue;
      }
      if (p.name().equals("END")) {
        String what = stack.isEmpty() ? "" : stack.pop();
        if (what.equals("VEVENT") && current != null) {
          events.add(current);
          current = null;
        }
        continue;
      }
      String top = stack.isEmpty() ? "" : stack.peek();
      switch (top) {
        case "VEVENT" -> {
          if (current != null) {
            current.props.add(p);
          }
        }
        case "VTIMEZONE" -> {
          if (p.name().equals("TZID")) {
            tzid = p.value().strip();
          }
        }
        case "STANDARD", "DAYLIGHT" -> {
          if (p.name().equals("TZOFFSETTO") && tzid != null) {
            ZoneOffset o = offset(p.value());
            // Постоянное смещение — у STANDARD; DAYLIGHT — только если другого нет.
            if (o != null && (top.equals("STANDARD") || !offsets.containsKey(tzid))) {
              offsets.put(tzid, o);
            }
          }
        }
        case "VCALENDAR" -> {
          if (p.name().equals("X-WR-TIMEZONE")) {
            calendarZone = p.value().strip();
          }
        }
        default -> {
          // VALARM и прочее внутри события — не про занятие
        }
      }
    }
    ZoneId floating = zone(calendarZone, offsets, fallback);
    return expandAll(events, offsets, floating, notBefore);
  }

  private static Result expandAll(
      List<Component> components,
      Map<String, ZoneOffset> offsets,
      ZoneId floating,
      long notBefore) {
    // Переносы и отмены одного занятия из серии: UID + исходное начало.
    Map<String, Component> overrides = new HashMap<>();
    List<Component> masters = new ArrayList<>();
    for (Component c : components) {
      Prop rid = c.get("RECURRENCE-ID");
      String uid = c.text("UID");
      if (rid != null && !uid.isEmpty()) {
        When w = when(rid, offsets, floating);
        if (w != null) {
          overrides.put(uid + "#" + w.instant().toEpochMilli(), c);
          continue;
        }
      }
      masters.add(c);
    }

    List<Event> out = new ArrayList<>();
    int allDay = 0;
    int cancelled = 0;
    int unsupported = 0;
    int past = 0;
    for (Component c : masters) {
      if ("CANCELLED".equalsIgnoreCase(c.text("STATUS"))) {
        cancelled++;
        continue;
      }
      Prop dtstart = c.get("DTSTART");
      When start = dtstart == null ? null : when(dtstart, offsets, floating);
      if (start == null) {
        continue;
      }
      if (start.allDay()) {
        allDay++;
        continue;
      }
      Duration length = length(c, start, offsets, floating);
      String uid = c.text("UID");
      Prop rrule = c.get("RRULE");
      List<LocalDateTime> starts;
      if (rrule == null) {
        starts = new ArrayList<>(List.of(start.local()));
      } else {
        Rule rule = rule(rrule.value(), start, offsets, floating);
        if (rule == null) {
          unsupported++;
          starts = new ArrayList<>(List.of(start.local()));
        } else {
          starts = expand(start.local(), start.zone(), rule);
        }
      }
      for (Prop rdate : c.all("RDATE")) {
        for (String v : rdate.value().split(",")) {
          When w = when(new Prop("RDATE", rdate.params(), v.split("/")[0]), offsets, floating);
          if (w != null && !w.allDay()) {
            LocalDateTime local = w.instant().atZone(start.zone()).toLocalDateTime();
            if (!starts.contains(local)) {
              starts.add(local);
            }
          }
        }
      }
      Set<Instant> exInstants = new HashSet<>();
      Set<LocalDate> exDates = new HashSet<>();
      for (Prop ex : c.all("EXDATE")) {
        for (String v : ex.value().split(",")) {
          When w = when(new Prop("EXDATE", ex.params(), v), offsets, floating);
          if (w == null) {
            continue;
          }
          if (w.allDay()) {
            exDates.add(w.local().toLocalDate());
          } else {
            exInstants.add(w.instant());
          }
        }
      }
      boolean series = rrule != null || !c.all("RDATE").isEmpty();
      for (LocalDateTime local : starts) {
        Instant at = local.atZone(start.zone()).toInstant();
        if (exInstants.contains(at) || exDates.contains(local.toLocalDate())) {
          continue;
        }
        String key = uid.isEmpty() ? null : uid + "#" + at.toEpochMilli();
        if (key != null && overrides.containsKey(key)) {
          continue; // занятие перенесли или отменили — возьмём его версию ниже
        }
        long s = at.toEpochMilli();
        long e = at.plus(length).toEpochMilli();
        if (e < notBefore) {
          past++;
          continue;
        }
        // Без UID — по названию и началу; одиночное — по UID (перенесли — то же занятие), в серии —
        // UID и начало по серии.
        String source =
            uid.isEmpty()
                ? "noid:" + Integer.toHexString(c.text("SUMMARY").hashCode()) + "#" + s
                : series ? key : uid;
        out.add(event(c, source, s, e));
        if (out.size() > MAX_EVENTS) {
          throw tooMany();
        }
      }
    }
    for (var o : overrides.entrySet()) {
      Component c = o.getValue();
      if ("CANCELLED".equalsIgnoreCase(c.text("STATUS"))) {
        cancelled++;
        continue;
      }
      Prop dtstart = c.get("DTSTART");
      When start = dtstart == null ? null : when(dtstart, offsets, floating);
      if (start == null || start.allDay()) {
        continue;
      }
      long s = start.instant().toEpochMilli();
      long e = start.instant().plus(length(c, start, offsets, floating)).toEpochMilli();
      if (e < notBefore) {
        past++;
        continue;
      }
      out.add(event(c, o.getKey(), s, e));
      if (out.size() > MAX_EVENTS) {
        throw tooMany();
      }
    }
    out.sort(Comparator.comparingLong(Event::start));
    return new Result(unique(out), allDay, cancelled, unsupported, past);
  }

  private static Invalid tooMany() {
    return new Invalid(
        "В файле больше " + MAX_EVENTS + " занятий – выгрузите расписание одного семестра");
  }

  /** Один и тот же UID у разных событий (так делают некоторые выгрузки) — ключ с началом. */
  private static List<Event> unique(List<Event> events) {
    Map<String, Integer> seen = new HashMap<>();
    List<Event> out = new ArrayList<>(events.size());
    for (Event e : events) {
      String source = e.source();
      if (seen.merge(source, 1, Integer::sum) > 1) {
        source = source + "@" + e.start();
      }
      out.add(
          new Event(
              source,
              e.summary(),
              e.location(),
              e.description(),
              e.categories(),
              e.start(),
              e.end()));
    }
    return out;
  }

  private static Event event(Component c, String source, long start, long end) {
    return new Event(
        source,
        c.text("SUMMARY"),
        c.text("LOCATION"),
        c.text("DESCRIPTION"),
        String.join(", ", c.all("CATEGORIES").stream().map(p -> unescape(p.value())).toList()),
        start,
        end);
  }

  /** Длина занятия: DTEND, иначе DURATION, иначе полтора часа; не больше 12 часов. */
  private static Duration length(
      Component c, When start, Map<String, ZoneOffset> offsets, ZoneId floating) {
    Duration d = null;
    Prop end = c.get("DTEND");
    if (end != null) {
      When w = when(end, offsets, floating);
      if (w != null && !w.allDay()) {
        d = Duration.between(start.instant(), w.instant());
      }
    } else if (c.get("DURATION") != null) {
      d = duration(c.get("DURATION").value());
    }
    if (d == null || d.isNegative() || d.isZero()) {
      return DEFAULT_LENGTH;
    }
    return d.compareTo(MAX_LENGTH) > 0 ? MAX_LENGTH : d;
  }

  // ---------- повторения ----------

  private static Rule rule(String raw, When start, Map<String, ZoneOffset> offsets, ZoneId fb) {
    Map<String, String> parts = new HashMap<>();
    for (String part : raw.strip().split(";")) {
      int eq = part.indexOf('=');
      if (eq > 0) {
        parts.put(
            part.substring(0, eq).strip().toUpperCase(Locale.ROOT),
            part.substring(eq + 1).strip().toUpperCase(Locale.ROOT));
      }
    }
    String freq = parts.getOrDefault("FREQ", "");
    if (!Set.of("DAILY", "WEEKLY", "MONTHLY").contains(freq)) {
      return null;
    }
    for (String unsupported :
        List.of("BYSETPOS", "BYWEEKNO", "BYYEARDAY", "BYHOUR", "BYMINUTE", "BYSECOND")) {
      if (parts.containsKey(unsupported)) {
        return null;
      }
    }
    try {
      int interval = Math.max(1, Integer.parseInt(parts.getOrDefault("INTERVAL", "1")));
      Integer count = parts.containsKey("COUNT") ? Integer.parseInt(parts.get("COUNT")) : null;
      Instant until = null;
      if (parts.containsKey("UNTIL")) {
        String u = parts.get("UNTIL");
        When w = when(new Prop("UNTIL", Map.of(), u), offsets, start.zone());
        if (w == null) {
          return null;
        }
        // UNTIL датой — включительно весь этот день.
        until =
            w.allDay()
                ? w.local()
                    .toLocalDate()
                    .plusDays(1)
                    .atStartOfDay(start.zone())
                    .toInstant()
                    .minusMillis(1)
                : w.instant();
      }
      List<DayOfWeek> byDay = new ArrayList<>();
      if (parts.containsKey("BYDAY")) {
        for (String d : parts.get("BYDAY").split(",")) {
          if (!d.matches("[A-Z]{2}")) {
            return null; // «1MO», «-1FR» — n-й день месяца: не для расписания пар
          }
          byDay.add(day(d));
        }
      }
      List<Integer> byMonthDay = new ArrayList<>();
      if (parts.containsKey("BYMONTHDAY")) {
        for (String d : parts.get("BYMONTHDAY").split(",")) {
          byMonthDay.add(Integer.parseInt(d));
        }
      }
      Set<Integer> byMonth = new HashSet<>();
      if (parts.containsKey("BYMONTH")) {
        for (String m : parts.get("BYMONTH").split(",")) {
          byMonth.add(Integer.parseInt(m));
        }
      }
      DayOfWeek wkst = parts.containsKey("WKST") ? day(parts.get("WKST")) : DayOfWeek.MONDAY;
      return new Rule(freq, interval, count, until, byDay, byMonthDay, byMonth, wkst);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static DayOfWeek day(String code) {
    return switch (code) {
      case "MO" -> DayOfWeek.MONDAY;
      case "TU" -> DayOfWeek.TUESDAY;
      case "WE" -> DayOfWeek.WEDNESDAY;
      case "TH" -> DayOfWeek.THURSDAY;
      case "FR" -> DayOfWeek.FRIDAY;
      case "SA" -> DayOfWeek.SATURDAY;
      case "SU" -> DayOfWeek.SUNDAY;
      default -> throw new IllegalArgumentException(code);
    };
  }

  /** Начала занятий серии по местному времени пояса начала; первое — всегда само DTSTART. */
  private static List<LocalDateTime> expand(LocalDateTime start, ZoneId zone, Rule r) {
    List<LocalDateTime> out = new ArrayList<>();
    out.add(start);
    int limit = Math.min(r.count() == null ? MAX_PER_EVENT : r.count(), MAX_PER_EVENT);
    LocalDateTime horizon = start.plus(HORIZON);
    switch (r.freq()) {
      case "DAILY" -> {
        for (long n = r.interval(); out.size() < limit; n += r.interval()) {
          LocalDateTime t = start.plusDays(n);
          if (over(t, zone, r, horizon)) {
            break;
          }
          if ((r.byDay().isEmpty() || r.byDay().contains(t.getDayOfWeek())) && month(t, r)) {
            out.add(t);
          }
        }
      }
      case "WEEKLY" -> {
        List<DayOfWeek> days =
            (r.byDay().isEmpty() ? List.of(start.getDayOfWeek()) : r.byDay())
                .stream()
                    .distinct()
                    .sorted(Comparator.comparingInt(d -> offset(d, r.weekStart())))
                    .toList();
        LocalDate week = start.toLocalDate().with(TemporalAdjusters.previousOrSame(r.weekStart()));
        weeks:
        for (long w = 0; ; w += r.interval()) {
          LocalDate monday = week.plusWeeks(w);
          if (monday.atStartOfDay().isAfter(horizon)) {
            break;
          }
          for (DayOfWeek d : days) {
            LocalDateTime t = monday.plusDays(offset(d, r.weekStart())).atTime(start.toLocalTime());
            if (!t.isAfter(start)) {
              continue;
            }
            if (over(t, zone, r, horizon)) {
              break weeks;
            }
            if (!month(t, r)) {
              continue;
            }
            out.add(t);
            if (out.size() >= limit) {
              break weeks;
            }
          }
        }
      }
      case "MONTHLY" -> {
        List<Integer> days =
            r.byMonthDay().isEmpty() ? List.of(start.getDayOfMonth()) : r.byMonthDay();
        months:
        for (long m = 0; ; m += r.interval()) {
          YearMonth ym = YearMonth.from(start).plusMonths(m);
          if (ym.atDay(1).atStartOfDay().isAfter(horizon)) {
            break;
          }
          List<LocalDateTime> inMonth = new ArrayList<>();
          for (int md : days) {
            int dom = md > 0 ? md : ym.lengthOfMonth() + md + 1;
            if (dom >= 1 && dom <= ym.lengthOfMonth()) {
              inMonth.add(ym.atDay(dom).atTime(start.toLocalTime()));
            }
          }
          inMonth.sort(null);
          for (LocalDateTime t : inMonth) {
            if (!t.isAfter(start)) {
              continue;
            }
            if (over(t, zone, r, horizon)) {
              break months;
            }
            if ((!r.byDay().isEmpty() && !r.byDay().contains(t.getDayOfWeek())) || !month(t, r)) {
              continue;
            }
            out.add(t);
            if (out.size() >= limit) {
              break months;
            }
          }
        }
      }
      default -> {
        // rule() других не возвращает
      }
    }
    return out;
  }

  private static int offset(DayOfWeek d, DayOfWeek weekStart) {
    return (d.getValue() - weekStart.getValue() + 7) % 7;
  }

  private static boolean over(LocalDateTime t, ZoneId zone, Rule r, LocalDateTime horizon) {
    return t.isAfter(horizon)
        || (r.until() != null && t.atZone(zone).toInstant().isAfter(r.until()));
  }

  private static boolean month(LocalDateTime t, Rule r) {
    return r.byMonth().isEmpty() || r.byMonth().contains(t.getMonthValue());
  }

  // ---------- время ----------

  private static When when(Prop p, Map<String, ZoneOffset> offsets, ZoneId floating) {
    String v = p.value().strip();
    try {
      boolean date = "DATE".equalsIgnoreCase(p.param("VALUE")) || v.matches("\\d{8}");
      ZoneId zone = zone(p.param("TZID"), offsets, floating);
      if (date) {
        return new When(LocalDate.parse(v.substring(0, 8), DATE).atStartOfDay(), zone, true);
      }
      boolean utc = v.endsWith("Z") || v.endsWith("z");
      String bare = utc ? v.substring(0, v.length() - 1) : v;
      LocalDateTime local =
          LocalDateTime.parse(bare, bare.length() == 13 ? DATE_TIME_SHORT : DATE_TIME);
      return new When(local, utc ? ZoneOffset.UTC : zone, false);
    } catch (DateTimeParseException | StringIndexOutOfBoundsException e) {
      return null;
    }
  }

  /** Пояс по TZID: имя IANA, имя из пути, название Windows, смещение из VTIMEZONE, «GMT+3». */
  static ZoneId zone(String tzid, Map<String, ZoneOffset> offsets, ZoneId fallback) {
    if (tzid == null || tzid.isBlank()) {
      return fallback;
    }
    String t = tzid.strip();
    try {
      return ZoneId.of(t);
    } catch (DateTimeException e) {
      // не IANA — пробуем дальше
    }
    Matcher m = IANA.matcher(t);
    if (m.find()) {
      try {
        return ZoneId.of(m.group(1));
      } catch (DateTimeException e) {
        // «Moscow/Standard» и подобное
      }
    }
    String win = WINDOWS_ZONES.get(t.toLowerCase(Locale.ROOT));
    if (win != null) {
      return ZoneId.of(win);
    }
    ZoneOffset o = offsets.get(t);
    if (o != null) {
      return o;
    }
    Matcher g = GMT_OFFSET.matcher(t);
    if (g.find()) {
      int h = Integer.parseInt(g.group(2));
      int min = g.group(3) == null ? 0 : Integer.parseInt(g.group(3));
      int sign = g.group(1).equals("-") ? -1 : 1;
      try {
        return ZoneOffset.ofHoursMinutes(sign * h, sign * min);
      } catch (DateTimeException e) {
        return fallback;
      }
    }
    return fallback;
  }

  /** «+0300», «-0530», «+03» → смещение. */
  private static ZoneOffset offset(String raw) {
    String v = raw.strip();
    Matcher m = Pattern.compile("([+-])(\\d{2})(\\d{2})?(\\d{2})?").matcher(v);
    if (!m.matches()) {
      return null;
    }
    int sign = m.group(1).equals("-") ? -1 : 1;
    int h = Integer.parseInt(m.group(2));
    int min = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
    try {
      return ZoneOffset.ofHoursMinutes(sign * h, sign * min);
    } catch (DateTimeException e) {
      return null;
    }
  }

  /** «PT1H30M», «P1D», «P1W», «-PT15M» → длительность; не разобрать — null. */
  static Duration duration(String raw) {
    Matcher m =
        Pattern.compile(
                "([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?")
            .matcher(raw.strip().toUpperCase(Locale.ROOT));
    if (!m.matches()) {
      return null;
    }
    Duration d = Duration.ZERO;
    if (m.group(2) != null) {
      d = d.plusDays(7L * Long.parseLong(m.group(2)));
    }
    if (m.group(3) != null) {
      d = d.plusDays(Long.parseLong(m.group(3)));
    }
    if (m.group(4) != null) {
      d = d.plusHours(Long.parseLong(m.group(4)));
    }
    if (m.group(5) != null) {
      d = d.plusMinutes(Long.parseLong(m.group(5)));
    }
    if (m.group(6) != null) {
      d = d.plusSeconds(Long.parseLong(m.group(6)));
    }
    return "-".equals(m.group(1)) ? d.negated() : d;
  }

  // ---------- строки ----------

  /** Строки файла; продолжение (начинается с пробела или табуляции) приклеивается к предыдущей. */
  static List<String> unfold(String text) {
    String t = text.startsWith("﻿") ? text.substring(1) : text;
    List<String> out = new ArrayList<>();
    for (String line : t.split("\r\n|\n|\r", -1)) {
      if (line.isEmpty()) {
        continue;
      }
      char first = line.charAt(0);
      if ((first == ' ' || first == '\t') && !out.isEmpty()) {
        out.set(out.size() - 1, out.getLast() + line.substring(1));
      } else {
        out.add(line);
      }
    }
    return out;
  }

  /** «ИМЯ;ПАРАМ=знач;ПАРАМ="в кавычках":значение» → свойство; двоеточие в кавычках — не конец. */
  private static Prop prop(String line) {
    int colon = -1;
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"') {
        quoted = !quoted;
      } else if (c == ':' && !quoted) {
        colon = i;
        break;
      }
    }
    if (colon <= 0) {
      return null;
    }
    List<String> head = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    quoted = false;
    for (int i = 0; i < colon; i++) {
      char c = line.charAt(i);
      if (c == '"') {
        quoted = !quoted;
      }
      if (c == ';' && !quoted) {
        head.add(cur.toString());
        cur.setLength(0);
      } else {
        cur.append(c);
      }
    }
    head.add(cur.toString());
    String name = head.getFirst().strip().toUpperCase(Locale.ROOT);
    Map<String, String> params = new LinkedHashMap<>();
    for (int i = 1; i < head.size(); i++) {
      String p = head.get(i);
      int eq = p.indexOf('=');
      if (eq > 0) {
        String value = p.substring(eq + 1).strip();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
          value = value.substring(1, value.length() - 1);
        }
        params.put(p.substring(0, eq).strip().toUpperCase(Locale.ROOT), value);
      }
    }
    return new Prop(name, params, line.substring(colon + 1));
  }

  /** Текст из файла: «\n» — перенос строки, «\,» «\;» «\\» — сами символы. */
  static String unescape(String v) {
    StringBuilder b = new StringBuilder(v.length());
    for (int i = 0; i < v.length(); i++) {
      char c = v.charAt(i);
      if (c == '\\' && i + 1 < v.length()) {
        char n = v.charAt(++i);
        b.append(n == 'n' || n == 'N' ? '\n' : n);
      } else {
        b.append(c);
      }
    }
    return b.toString();
  }
}
