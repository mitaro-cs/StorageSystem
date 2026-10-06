package app.groupbase.schedule;

import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Permission;
import app.groupbase.config.GroupbaseProperties;
import app.groupbase.content.Access;
import app.groupbase.content.HomeworkService;
import app.groupbase.content.MaterialService;
import app.groupbase.content.NewsService;
import app.groupbase.content.SubjectService;
import app.groupbase.store.GroupStore;
import app.groupbase.store.Rows;
import app.groupbase.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Расписание пар группы: загрузка из файла календаря (.ics), пары вручную, страница пары с
 * заданиями и материалами к ней. Видят пары все в группе; вести расписание — право MANAGE_SCHEDULE
 * (староста, замы, модератор; студентам — если включит староста).
 */
@Service
public class LessonService {

  public record Can(boolean edit) {}

  public record Lesson(
      long id,
      long groupId,
      NewsService.SubjectRef subject,
      String title,
      LessonKind kind,
      long startsAt,
      long endsAt,
      String place,
      String teacher,
      String note,
      int homework,
      int materials,
      boolean cancelled,
      Can can) {}

  /** Соседняя пара того же предмета — листать лекции по порядку. */
  public record Neighbor(long id, long startsAt, LessonKind kind) {}

  public record Detail(
      Lesson lesson,
      List<HomeworkService.Item> homework,
      List<MaterialService.Item> materials,
      Neighbor prev,
      Neighbor next) {}

  /**
   * Пара вручную. При изменении null — не менять; subjectId 0 — без предмета.
   *
   * @param repeatWeeks при создании: ещё столько же пар через неделю (0–30)
   */
  public record Input(
      Long subjectId,
      String title,
      String kind,
      Long startsAt,
      Long endsAt,
      String place,
      String teacher,
      String note,
      Integer repeatWeeks) {}

  public record IcsInput(String ics) {}

  /** Одно название из файла: сколько пар, каких видов и какой это предмет группы. */
  public record Title(
      String key,
      String name,
      int count,
      Map<LessonKind, Integer> kinds,
      Long subjectId,
      String teacher,
      List<String> places) {}

  /**
   * @param existing пар у группы уже есть
   * @param replaced из них уйдут при замене: загружены из файла раньше, стоят с первой даты нового
   *     файла и дальше, а в нём их нет
   */
  public record Preview(
      int lessons,
      long from,
      long to,
      List<Title> titles,
      int allDay,
      int cancelled,
      int unsupported,
      int past,
      int existing,
      int replaced) {}

  /**
   * @param action subject — к предмету subjectId, create — создать предмет, none — без предмета,
   *     skip — не загружать
   */
  public record Choice(String key, String action, Long subjectId) {}

  /**
   * @param replace убрать пары прежней загрузки с первой даты файла, которых в нём нет
   */
  public record ImportInput(String ics, List<Choice> choices, Boolean replace) {}

  public record Imported(int created, int updated, int deleted, int skipped, int subjects) {}

  private record Row(
      long id,
      long groupId,
      Long subjectId,
      String subjectName,
      String subjectColor,
      String title,
      String kind,
      long startsAt,
      long endsAt,
      String place,
      String teacher,
      String note,
      int homework,
      int materials,
      boolean cancelled) {}

  /** Пара из прежней загрузки — сравнить с новой и обновить только изменившееся. */
  private record Existing(
      long id,
      String source,
      Long subjectId,
      String title,
      String kind,
      long start,
      long end,
      String place,
      String teacher) {}

  private static final long DAY = Duration.ofDays(1).toMillis();

  /** Файл календаря — текстом; расписание семестра занимает десятки килобайт. */
  static final int MAX_FILE = 3 * 1024 * 1024;

  /** Прошлые семестры из файла не нужны: пары, закончившиеся раньше, не загружаются. */
  static final Duration KEEP_PAST = Duration.ofDays(120);

  static final int MAX_REPEAT = 30;

  /** Цвета новых предметов из файла — различимые, как в редакторе предмета. */
  private static final String[] COLORS = {
    "#4f7df5", "#1fa37a", "#e0633a", "#a35cf0", "#d9a21b", "#1f9bb8", "#d9487e", "#5b6ee1"
  };

  private static final String SELECT =
      """
      SELECT l.*, s.name AS subject_name, s.color AS subject_color, s.teacher AS subject_teacher,
        (SELECT count(*) FROM homework h WHERE h.lesson_id = l.id AND h.hidden = 0) AS hw_count,
        (SELECT count(*) FROM materials m WHERE m.lesson_id = l.id AND m.status = 'published'
          AND m.hidden = 0) AS mat_count
      FROM lessons l LEFT JOIN subjects s ON s.id = l.subject_id
      """;

  private final JdbcClient db;
  private final Access access;
  private final GroupStore groups;
  private final SubjectService subjects;
  private final HomeworkService homework;
  private final MaterialService materials;
  private final AuditService audit;
  private final Clock clock;
  private final ZoneId zone;

  public LessonService(
      JdbcClient db,
      Access access,
      GroupStore groups,
      SubjectService subjects,
      HomeworkService homework,
      MaterialService materials,
      AuditService audit,
      Clock clock,
      GroupbaseProperties props) {
    this.db = db;
    this.access = access;
    this.groups = groups;
    this.subjects = subjects;
    this.homework = homework;
    this.materials = materials;
    this.audit = audit;
    this.clock = clock;
    this.zone = props.timezone();
  }

  private List<Row> query(String where, Map<String, Object> params) {
    var q = db.sql(SELECT + " WHERE " + where);
    for (var e : params.entrySet()) {
      q = q.param(e.getKey(), e.getValue());
    }
    return q.query(
            (rs, i) ->
                new Row(
                    rs.getLong("id"),
                    rs.getLong("group_id"),
                    Rows.longOrNull(rs, "subject_id"),
                    rs.getString("subject_name"),
                    rs.getString("subject_color"),
                    rs.getString("title"),
                    rs.getString("kind"),
                    rs.getLong("starts_at"),
                    rs.getLong("ends_at"),
                    rs.getString("place"),
                    // Нет в файле календаря — преподаватель из карточки предмета.
                    rs.getString("teacher").isBlank()
                        ? java.util.Objects.toString(rs.getString("subject_teacher"), "")
                        : rs.getString("teacher"),
                    rs.getString("note"),
                    rs.getInt("hw_count"),
                    rs.getInt("mat_count"),
                    rs.getInt("cancelled") == 1))
        .list();
  }

  private List<Lesson> views(Actor actor, List<Row> rows) {
    Map<Long, Boolean> edit = new HashMap<>();
    List<Lesson> out = new ArrayList<>(rows.size());
    for (Row r : rows) {
      boolean can =
          edit.computeIfAbsent(
              r.groupId(), g -> access.can(actor, Permission.MANAGE_SCHEDULE, List.of(g)));
      out.add(
          new Lesson(
              r.id(),
              r.groupId(),
              r.subjectId() == null
                  ? null
                  : new NewsService.SubjectRef(r.subjectId(), r.subjectName(), r.subjectColor()),
              r.title(),
              LessonKind.of(r.kind()),
              r.startsAt(),
              r.endsAt(),
              r.place(),
              r.teacher(),
              r.note(),
              r.homework(),
              r.materials(),
              r.cancelled(),
              new Can(can)));
    }
    return out;
  }

  public long startOfToday() {
    return LocalDate.now(clock.withZone(zone)).atStartOfDay(zone).toInstant().toEpochMilli();
  }

  // ---------- просмотр ----------

  /**
   * Пары за период: общий список — без предметов, скрытых у себя (другая подгруппа); у предмета —
   * все его пары. Период — до двух месяцев, у одного предмета — до года (весь семестр списком).
   */
  public List<Lesson> list(Actor actor, Long group, Long subject, Long from, Long to) {
    if (from == null || to == null || to <= from) {
      throw ApiException.badRequest("Укажите период: from и to");
    }
    if (to - from > (subject == null ? 62 : 400) * DAY) {
      throw ApiException.badRequest(
          subject == null ? "Период – не длиннее двух месяцев" : "Период – не длиннее года");
    }
    List<Long> scope = access.scope(actor, group);
    if (scope.isEmpty()) {
      return List.of();
    }
    Map<String, Object> p = new HashMap<>();
    p.put("g", scope);
    p.put("from", from);
    p.put("to", to);
    p.put("uid", actor.id());
    StringBuilder where =
        new StringBuilder("l.group_id IN (:g) AND l.starts_at < :to AND l.ends_at > :from");
    if (subject != null) {
      subjects.visible(actor, subject);
      p.put("subject", subject);
      where.append(" AND l.subject_id = :subject");
    } else {
      where.append(
          " AND NOT EXISTS (SELECT 1 FROM subject_hidden sh"
              + " WHERE sh.user_id = :uid AND sh.subject_id = l.subject_id)");
    }
    where.append(" ORDER BY l.starts_at, l.id");
    return views(actor, query(where.toString(), p));
  }

  /** Для «Сегодня»: пары сегодня и завтра (вечером видно, к чему готовиться утром). */
  public List<Lesson> today(Actor actor, Long group) {
    long from = startOfToday();
    return list(actor, group, null, from, from + 2 * DAY);
  }

  /** Для офлайн-копии: видимые пары среди ids или (ids == null) закончившиеся не раньше since. */
  public List<Lesson> visible(Actor actor, Collection<Long> ids, long since) {
    List<Long> scope = access.visibleGroups(actor);
    if (scope.isEmpty() || (ids != null && ids.isEmpty())) {
      return List.of();
    }
    Map<String, Object> p = new HashMap<>();
    p.put("g", scope);
    String where = "l.group_id IN (:g)";
    if (ids == null) {
      p.put("since", since);
      where += " AND l.ends_at >= :since";
    } else {
      p.put("ids", List.copyOf(ids));
      where += " AND l.id IN (:ids)";
    }
    return views(actor, query(where + " ORDER BY l.id", p));
  }

  private Row row(Actor actor, long id) {
    List<Row> rows = query("l.id = :id", Map.of("id", id));
    if (rows.isEmpty()) {
      throw ApiException.notFound();
    }
    Row r = rows.getFirst();
    access.requireSee(actor, List.of(r.groupId()));
    return r;
  }

  /** Страница пары: что задано к ней, материалы, соседние пары того же предмета. */
  public Detail get(Actor actor, long id) {
    Row r = row(actor, id);
    Lesson l = views(actor, List.of(r)).getFirst();
    Neighbor prev = null;
    Neighbor next = null;
    if (r.subjectId() != null) {
      prev = neighbor(r, "<", "DESC");
      next = neighbor(r, ">", "ASC");
    }
    return new Detail(l, homework.byLesson(actor, id), materials.byLesson(actor, id), prev, next);
  }

  private Neighbor neighbor(Row r, String cmp, String order) {
    return db.sql(
            "SELECT id, starts_at, kind FROM lessons WHERE group_id = ? AND subject_id = ?"
                + " AND (starts_at "
                + cmp
                + " ? OR (starts_at = ? AND id "
                + cmp
                + " ?)) ORDER BY starts_at "
                + order
                + ", id "
                + order
                + " LIMIT 1")
        .params(r.groupId(), r.subjectId(), r.startsAt(), r.startsAt(), r.id())
        .query(
            (rs, i) -> new Neighbor(rs.getLong(1), rs.getLong(2), LessonKind.of(rs.getString(3))))
        .optional()
        .orElse(null);
  }

  // ---------- изменение ----------

  private void requireManage(Actor actor, long groupId) {
    access.requireAll(actor, Permission.MANAGE_SCHEDULE, List.of(groupId));
  }

  private record Clean(
      Long subjectId,
      String title,
      LessonKind kind,
      long start,
      long end,
      String place,
      String teacher,
      String note) {}

  /** Проверка полей; base — прежняя пара (при изменении), null — новая. */
  private Clean clean(long groupId, Input in, Row base) {
    Long subjectId = base == null ? null : base.subjectId();
    if (in.subjectId() != null) {
      subjectId = in.subjectId() == 0 ? null : in.subjectId();
      if (subjectId != null && !linked(subjectId, groupId)) {
        throw ApiException.invalid("subjectId", "Предмет не из этой группы");
      }
    }
    String title = in.title() == null ? (base == null ? "" : base.title()) : in.title().strip();
    if (title.isEmpty() && subjectId != null) {
      title = subjectName(subjectId);
    }
    if (title.isEmpty() || title.length() > 200) {
      throw ApiException.invalid("title", "Название пары: от 1 до 200 символов");
    }
    LessonKind kind =
        in.kind() == null || in.kind().isBlank()
            ? (base == null ? LessonKind.OTHER : LessonKind.of(base.kind()))
            : LessonKind.of(in.kind());
    Long start = in.startsAt() == null ? (base == null ? null : base.startsAt()) : in.startsAt();
    Long end = in.endsAt() == null ? (base == null ? null : base.endsAt()) : in.endsAt();
    if (start == null || end == null) {
      throw ApiException.invalid("startsAt", "Укажите, когда пара начинается и заканчивается");
    }
    if (end <= start || end - start > Ics.MAX_LENGTH.toMillis()) {
      throw ApiException.invalid("endsAt", "Пара заканчивается позже начала и длится до 12 часов");
    }
    long now = clock.millis();
    if (start < now - 2 * 366 * DAY || start > now + 2 * 366 * DAY) {
      throw ApiException.invalid("startsAt", "Дата вне разумного диапазона");
    }
    String place = text(in.place(), base == null ? "" : base.place(), 80, "place", "Аудитория");
    String teacher =
        text(in.teacher(), base == null ? "" : base.teacher(), 120, "teacher", "Преподаватель");
    String note = in.note() == null ? (base == null ? "" : base.note()) : in.note().strip();
    if (note.length() > 2000) {
      throw ApiException.invalid("note", "Тема и заметка – до 2000 символов");
    }
    return new Clean(subjectId, title, kind, start, end, place, teacher, note);
  }

  private static String text(String raw, String keep, int max, String field, String label) {
    String v = raw == null ? keep : raw.strip().replaceAll("\\s+", " ");
    if (v.length() > max) {
      throw ApiException.invalid(field, label + " – до " + max + " символов");
    }
    return v;
  }

  private boolean linked(long subjectId, long groupId) {
    return db.sql("SELECT 1 FROM subject_groups WHERE subject_id = ? AND group_id = ?")
        .params(subjectId, groupId)
        .query(Integer.class)
        .optional()
        .isPresent();
  }

  private String subjectName(long subjectId) {
    return db.sql("SELECT name FROM subjects WHERE id = ?")
        .param(subjectId)
        .query(String.class)
        .optional()
        .orElse("");
  }

  /** Новая пара; repeatWeeks — ещё столько же через неделю, в то же время по часам сайта. */
  @Transactional
  public List<Lesson> create(Actor actor, long groupId, Input in) {
    groups.find(groupId).orElseThrow(ApiException::notFound);
    requireManage(actor, groupId);
    Clean c = clean(groupId, in, null);
    int repeat = in.repeatWeeks() == null ? 0 : in.repeatWeeks();
    if (repeat < 0 || repeat > MAX_REPEAT) {
      throw ApiException.invalid("repeatWeeks", "Повторить – от 0 до " + MAX_REPEAT + " недель");
    }
    long now = clock.millis();
    long length = c.end() - c.start();
    List<Long> ids = new ArrayList<>();
    for (int w = 0; w <= repeat; w++) {
      long start =
          Instant.ofEpochMilli(c.start()).atZone(zone).plusWeeks(w).toInstant().toEpochMilli();
      ids.add(insert(groupId, c, start, start + length, null, actor.id(), now));
    }
    audit.log(
        actor,
        groupId,
        "lesson.create",
        "lesson",
        ids.getFirst(),
        Map.of("title", c.title(), "count", String.valueOf(ids.size())));
    return views(actor, query("l.id IN (:ids) ORDER BY l.starts_at", Map.of("ids", ids)));
  }

  private long insert(
      long groupId, Clean c, long start, long end, String source, long by, long now) {
    return db.sql(
            """
            INSERT INTO lessons (group_id, subject_id, title, kind, starts_at, ends_at, place,
                                 teacher, note, source, created_by, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
            """)
        .params(
            groupId,
            c.subjectId(),
            c.title(),
            c.kind().id(),
            start,
            end,
            c.place(),
            c.teacher(),
            c.note(),
            source,
            by,
            now,
            now)
        .query(Long.class)
        .single();
  }

  @Transactional
  public Lesson update(Actor actor, long id, Input in) {
    Row r = row(actor, id);
    requireManage(actor, r.groupId());
    Clean c = clean(r.groupId(), in, r);
    db.sql(
            """
            UPDATE lessons SET subject_id = ?, title = ?, kind = ?, starts_at = ?, ends_at = ?,
              place = ?, teacher = ?, note = ?, updated_at = ? WHERE id = ?
            """)
        .params(
            c.subjectId(),
            c.title(),
            c.kind().id(),
            c.start(),
            c.end(),
            c.place(),
            c.teacher(),
            c.note(),
            clock.millis(),
            id)
        .update();
    audit.log(actor, r.groupId(), "lesson.update", "lesson", id, Map.of("title", c.title()));
    return views(actor, query("l.id = :id", Map.of("id", id))).getFirst();
  }

  /** Отметить, что пары не было (или вернуть). */
  @Transactional
  public Lesson setCancelled(Actor actor, long id, boolean value) {
    Row r = row(actor, id);
    requireManage(actor, r.groupId());
    db.sql("UPDATE lessons SET cancelled = ?, updated_at = ? WHERE id = ?")
        .params(value ? 1 : 0, clock.millis(), id)
        .update();
    audit.log(
        actor,
        r.groupId(),
        value ? "lesson.cancel" : "lesson.restore",
        "lesson",
        id,
        Map.of("title", r.title()));
    return views(actor, query("l.id = :id", Map.of("id", id))).getFirst();
  }

  /** Удалить пару; задания и материалы к ней остаются — просто без пары. */
  @Transactional
  public void delete(Actor actor, long id) {
    Row r = row(actor, id);
    requireManage(actor, r.groupId());
    db.sql("DELETE FROM lessons WHERE id = ?").param(id).update();
    audit.log(actor, r.groupId(), "lesson.delete", "lesson", id, Map.of("title", r.title()));
  }

  /** Очистить расписание группы целиком (например, перед загрузкой нового семестра). */
  @Transactional
  public int clear(Actor actor, long groupId) {
    groups.find(groupId).orElseThrow(ApiException::notFound);
    requireManage(actor, groupId);
    int n = db.sql("DELETE FROM lessons WHERE group_id = ?").param(groupId).update();
    audit.log(
        actor, groupId, "schedule.clear", "group", groupId, Map.of("count", String.valueOf(n)));
    return n;
  }

  // ---------- файл календаря ----------

  private Ics.Result parse(String ics) {
    if (ics == null || ics.isBlank()) {
      throw ApiException.invalid("ics", "Выберите файл календаря (.ics)");
    }
    if (ics.length() > MAX_FILE) {
      throw ApiException.invalid("ics", "Файл больше 3 МБ – это не расписание одной группы");
    }
    Ics.Result r;
    try {
      r = Ics.parse(ics, zone, clock.millis() - KEEP_PAST.toMillis());
    } catch (Ics.Invalid e) {
      throw ApiException.invalid("ics", e.getMessage());
    }
    if (r.events().isEmpty()) {
      throw ApiException.invalid(
          "ics", "В файле нет пар: только прошедшие, отменённые или на весь день");
    }
    return r;
  }

  private List<Titles.Subject> groupSubjects(long groupId) {
    return db.sql(
            """
            SELECT s.id, s.name FROM subjects s JOIN subject_groups sg ON sg.subject_id = s.id
            WHERE sg.group_id = ? AND s.archived_at IS NULL ORDER BY s.name
            """)
        .param(groupId)
        .query((rs, i) -> new Titles.Subject(rs.getLong(1), rs.getString(2)))
        .list();
  }

  private Map<String, Existing> existing(long groupId) {
    Map<String, Existing> out = new HashMap<>();
    db.sql(
            """
            SELECT id, source, subject_id, title, kind, starts_at, ends_at, place, teacher
            FROM lessons WHERE group_id = ? AND source IS NOT NULL
            """)
        .param(groupId)
        .query(
            rs -> {
              out.put(
                  rs.getString(2),
                  new Existing(
                      rs.getLong(1),
                      rs.getString(2),
                      Rows.longOrNull(rs, "subject_id"),
                      rs.getString(4),
                      rs.getString(5),
                      rs.getLong(6),
                      rs.getLong(7),
                      rs.getString(8),
                      rs.getString(9)));
            });
    return out;
  }

  /**
   * Пары прежней загрузки, которые уйдут при замене: с первой даты нового файла и дальше, но в нём
   * их нет. Прошлое до начала файла остаётся — с темами, заданиями и материалами.
   */
  private static List<Existing> gone(Collection<Existing> old, Set<String> sources, long from) {
    return old.stream().filter(x -> !sources.contains(x.source()) && x.start() >= from).toList();
  }

  /** Одно название в предпросмотре: сколько пар, каких видов, преподаватель, аудитории. */
  private static final class Acc {
    final String name;
    final Map<LessonKind, Integer> kinds = new EnumMap<>(LessonKind.class);
    final List<String> places = new ArrayList<>();
    String teacher = "";
    int count;

    Acc(String name) {
      this.name = name;
    }

    void add(Titles.Parsed t) {
      count++;
      kinds.merge(t.kind(), 1, Integer::sum);
      if (teacher.isEmpty()) {
        teacher = t.teacher();
      }
      if (!t.place().isEmpty() && places.size() < 3 && !places.contains(t.place())) {
        places.add(t.place());
      }
    }
  }

  /** Что в файле: названия с подобранными предметами группы, сколько пар и за какие даты. */
  public Preview preview(Actor actor, long groupId, String ics) {
    groups.find(groupId).orElseThrow(ApiException::notFound);
    requireManage(actor, groupId);
    Ics.Result r = parse(ics);
    List<Titles.Subject> subjectList = groupSubjects(groupId);
    Map<String, Acc> titles = new LinkedHashMap<>();
    long from = Long.MAX_VALUE;
    long to = Long.MIN_VALUE;
    Set<String> sources = new HashSet<>();
    for (Ics.Event e : r.events()) {
      Titles.Parsed t = Titles.parse(e.summary(), e.description(), e.location(), e.categories());
      titles.computeIfAbsent(t.key(), k -> new Acc(t.name())).add(t);
      from = Math.min(from, e.start());
      to = Math.max(to, e.end());
      sources.add(e.source());
    }
    List<Title> list =
        titles.entrySet().stream()
            .map(
                x ->
                    new Title(
                        x.getKey(),
                        x.getValue().name,
                        x.getValue().count,
                        x.getValue().kinds,
                        Titles.match(x.getValue().name, subjectList),
                        x.getValue().teacher,
                        x.getValue().places))
            .sorted(Comparator.comparingInt(Title::count).reversed())
            .toList();
    int existingCount =
        db.sql("SELECT count(*) FROM lessons WHERE group_id = ?")
            .param(groupId)
            .query(Integer.class)
            .single();
    int replaced = gone(existing(groupId).values(), sources, from).size();
    return new Preview(
        r.events().size(),
        from,
        to,
        list,
        r.allDay(),
        r.cancelled(),
        r.unsupported(),
        r.past(),
        existingCount,
        replaced);
  }

  /**
   * Загрузить пары из файла. Повторная загрузка того же (или обновлённого) файла обновляет пары, а
   * не дублирует: тема, задания и материалы к ним остаются. Пары, добавленные вручную, не трогаем.
   */
  @Transactional
  public Imported importIcs(Actor actor, long groupId, ImportInput in) {
    groups.find(groupId).orElseThrow(ApiException::notFound);
    requireManage(actor, groupId);
    Ics.Result r = parse(in.ics());
    Map<String, Choice> choices = new HashMap<>();
    if (in.choices() != null) {
      for (Choice c : in.choices()) {
        if (c != null && c.key() != null) {
          choices.put(c.key(), c);
        }
      }
    }
    List<Titles.Subject> subjectList = groupSubjects(groupId);
    Set<Long> groupSubjectIds = new HashSet<>();
    subjectList.forEach(s -> groupSubjectIds.add(s.id()));
    Map<String, Long> made = new HashMap<>();
    int[] newSubjects = {0};
    Map<String, Existing> old = existing(groupId);
    Set<String> sources = new HashSet<>();
    long now = clock.millis();
    long from = Long.MAX_VALUE;
    int created = 0;
    int updated = 0;
    int skipped = 0;
    for (Ics.Event e : r.events()) {
      Titles.Parsed t = Titles.parse(e.summary(), e.description(), e.location(), e.categories());
      Choice c = choices.get(t.key());
      String action = c == null || c.action() == null ? "auto" : c.action();
      if (action.equals("skip")) {
        skipped++;
        continue;
      }
      Long subjectId;
      if (action.equals("subject")) {
        if (c.subjectId() == null || !groupSubjectIds.contains(c.subjectId())) {
          throw ApiException.invalid("choices", "Предмет «" + t.name() + "» не из этой группы");
        }
        subjectId = c.subjectId();
      } else if (action.equals("create")) {
        // Такой предмет уже есть (тот же файл загружают второй раз) — не дублируем.
        subjectId =
            made.computeIfAbsent(
                t.key(),
                k ->
                    subjectList.stream()
                        .filter(x -> Titles.key(x.name()).equals(k))
                        .map(Titles.Subject::id)
                        .findFirst()
                        .orElseGet(
                            () -> {
                              newSubjects[0]++;
                              return createSubject(actor, groupId, t);
                            }));
      } else if (action.equals("none")) {
        subjectId = null;
      } else {
        subjectId = Titles.match(t.name(), subjectList);
      }
      sources.add(e.source());
      from = Math.min(from, e.start());
      Clean clean =
          new Clean(subjectId, t.name(), t.kind(), e.start(), e.end(), t.place(), t.teacher(), "");
      Existing x = old.get(e.source());
      if (x == null) {
        insert(groupId, clean, e.start(), e.end(), e.source(), actor.id(), now);
        created++;
      } else if (!Objects.equals(x.subjectId(), subjectId)
          || !x.title().equals(t.name())
          || !x.kind().equals(t.kind().id())
          || x.start() != e.start()
          || x.end() != e.end()
          || !x.place().equals(t.place())
          || !x.teacher().equals(t.teacher())) {
        db.sql(
                """
                UPDATE lessons SET subject_id = ?, title = ?, kind = ?, starts_at = ?, ends_at = ?,
                  place = ?, teacher = ?, updated_at = ? WHERE id = ?
                """)
            .params(
                subjectId,
                t.name(),
                t.kind().id(),
                e.start(),
                e.end(),
                t.place(),
                t.teacher(),
                now,
                x.id())
            .update();
        updated++;
      }
    }
    int deleted = 0;
    if (!Boolean.FALSE.equals(in.replace()) && !sources.isEmpty()) {
      for (Existing x : gone(old.values(), sources, from)) {
        deleted += db.sql("DELETE FROM lessons WHERE id = ?").param(x.id()).update();
      }
    }
    audit.log(
        actor,
        groupId,
        "schedule.import",
        "group",
        groupId,
        Map.of(
            "created", String.valueOf(created),
            "updated", String.valueOf(updated),
            "deleted", String.valueOf(deleted)));
    return new Imported(created, updated, deleted, skipped, newSubjects[0]);
  }

  private long createSubject(Actor actor, long groupId, Titles.Parsed t) {
    if (!access.can(actor, Permission.MANAGE_SUBJECTS, List.of(groupId))) {
      throw ApiException.forbidden("Создать предмет может староста – выберите существующий");
    }
    String name = t.name().length() > 80 ? t.name().substring(0, 80).strip() : t.name();
    String teacher = t.teacher().length() > 80 ? t.teacher().substring(0, 80) : t.teacher();
    String color = COLORS[Math.floorMod(t.key().hashCode(), COLORS.length)];
    return subjects
        .create(actor, groupId, new SubjectService.Input(name, teacher, color, null))
        .id();
  }
}
