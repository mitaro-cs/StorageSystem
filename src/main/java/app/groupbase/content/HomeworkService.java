package app.groupbase.content;

import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Permission;
import app.groupbase.config.GroupbaseProperties;
import app.groupbase.store.GroupStore;
import app.groupbase.store.People;
import app.groupbase.store.Person;
import app.groupbase.store.Rows;
import app.groupbase.web.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Домашние задания: дедлайны, целевые группы для общих предметов, личная отметка «выполнено». */
@Service
public class HomeworkService {

  public enum View {
    WEEK,
    OVERDUE,
    RANGE,
    ALL
  }

  /**
   * @param difficulty сложность 1–3; при изменении null — не менять, 0 — убрать
   */
  public record Input(
      Long subjectId,
      String title,
      String body,
      Long dueAt,
      List<Long> groupIds,
      List<Long> attachments,
      Integer difficulty) {}

  public record Can(boolean edit, boolean delete, boolean hide) {}

  public record Item(
      long id,
      String title,
      String bodyMd,
      String bodyHtml,
      long dueAt,
      Integer difficulty,
      boolean done,
      boolean hidden,
      long createdAt,
      long updatedAt,
      Person author,
      NewsService.SubjectRef subject,
      List<SubjectStore.GroupRef> groups,
      int comments,
      List<MaterialService.FileInfo> attachments,
      Can can) {}

  public record Published(
      long id, Set<Long> groups, long authorId, String title, long dueAt, String subjectName) {}

  private record Row(
      long id,
      long subjectId,
      String subjectName,
      String subjectColor,
      Long authorId,
      String title,
      String bodyMd,
      String bodyHtml,
      long dueAt,
      Integer difficulty,
      boolean hidden,
      long createdAt,
      long updatedAt,
      boolean done,
      int comments) {}

  private static final long DAY = 24L * 60 * 60 * 1000;

  private final JdbcClient db;
  private final Targets targets;
  private final Audience audience;
  private final Access access;
  private final People people;
  private final GroupStore groups;
  private final AuditService audit;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final ZoneId zone;

  public HomeworkService(
      JdbcClient db,
      Targets targets,
      Audience audience,
      Access access,
      People people,
      GroupStore groups,
      AuditService audit,
      ApplicationEventPublisher events,
      Clock clock,
      GroupbaseProperties props) {
    this.db = db;
    this.targets = targets;
    this.audience = audience;
    this.access = access;
    this.people = people;
    this.groups = groups;
    this.audit = audit;
    this.events = events;
    this.clock = clock;
    this.zone = props.timezone();
  }

  private static final String SELECT =
      """
      SELECT h.*, s.name AS subject_name, s.color AS subject_color,
        EXISTS (SELECT 1 FROM homework_done d
          WHERE d.homework_id = h.id AND d.user_id = :uid) AS done,
        (SELECT count(*) FROM comments c
          WHERE c.target_type = 'homework' AND c.target_id = h.id AND c.hidden = 0) AS comment_count
      FROM homework h JOIN subjects s ON s.id = h.subject_id
      """;

  private List<Row> query(String where, Map<String, Object> params) {
    var q = db.sql(SELECT + " WHERE " + where);
    for (var e : params.entrySet()) {
      q = q.param(e.getKey(), e.getValue());
    }
    return q.query(
            (rs, i) ->
                new Row(
                    rs.getLong("id"),
                    rs.getLong("subject_id"),
                    rs.getString("subject_name"),
                    rs.getString("subject_color"),
                    Rows.longOrNull(rs, "author_id"),
                    rs.getString("title"),
                    rs.getString("body_md"),
                    rs.getString("body_html"),
                    rs.getLong("due_at"),
                    Rows.intOrNull(rs, "difficulty"),
                    Rows.bool(rs, "hidden"),
                    rs.getLong("created_at"),
                    rs.getLong("updated_at"),
                    Rows.bool(rs, "done"),
                    rs.getInt("comment_count")))
        .list();
  }

  public long startOfToday() {
    return LocalDate.now(clock.withZone(zone)).atStartOfDay(zone).toInstant().toEpochMilli();
  }

  public List<Item> list(
      Actor actor, View view, Long group, Long subject, Long from, Long to, Integer limit) {
    List<Long> scope = access.scope(actor, group);
    if (scope.isEmpty()) {
      return List.of();
    }
    Set<Long> mod = access.groupsWith(actor, Permission.MODERATE_CONTENT, scope);
    Map<String, Object> p = new HashMap<>();
    p.put("g", scope);
    p.put("mod", Targets.nonEmpty(mod));
    p.put("uid", actor.id());
    p.put("subject", subject);
    StringBuilder where =
        new StringBuilder(
            """
            EXISTS (SELECT 1 FROM homework_targets t WHERE t.homework_id = h.id AND t.group_id IN (:g))
            AND (:subject IS NULL OR h.subject_id = :subject)
            AND (h.hidden = 0 OR h.author_id = :uid OR EXISTS (
              SELECT 1 FROM homework_targets t2 WHERE t2.homework_id = h.id AND t2.group_id IN (:mod)))
            """);
    long now = clock.millis();
    switch (view) {
      case WEEK -> {
        p.put("from", startOfToday());
        // Сегодня и ещё 7 полных дней: задание «через неделю, 23:59» попадает в список.
        p.put("to", startOfToday() + 8 * DAY);
        where.append(" AND h.due_at >= :from AND h.due_at < :to ORDER BY h.due_at");
      }
      case OVERDUE -> {
        p.put("now", now);
        p.put("since", now - 60 * DAY);
        where.append(
            " AND h.due_at < :now AND h.due_at >= :since AND NOT done ORDER BY h.due_at DESC");
      }
      case RANGE -> {
        if (from == null || to == null || to <= from || to - from > 62 * DAY) {
          throw ApiException.badRequest("Укажите период не длиннее двух месяцев");
        }
        p.put("from", from);
        p.put("to", to);
        where.append(" AND h.due_at >= :from AND h.due_at < :to ORDER BY h.due_at");
      }
      case ALL -> {
        p.put("limit", limit == null ? 100 : Math.min(limit, 200));
        where.append(" ORDER BY h.due_at DESC LIMIT :limit");
      }
    }
    return views(actor, query(where.toString(), p));
  }

  /**
   * Для офлайн-синхронизации: видимые задания среди {@code ids} или, если ids == null, все видимые
   * со сроком не раньше {@code since}.
   */
  public List<Item> visible(Actor actor, java.util.Collection<Long> ids, long since) {
    List<Long> scope = access.visibleGroups(actor);
    if (scope.isEmpty() || (ids != null && ids.isEmpty())) {
      return List.of();
    }
    Map<String, Object> p = new HashMap<>();
    p.put("g", scope);
    p.put("mod", Targets.nonEmpty(access.groupsWith(actor, Permission.MODERATE_CONTENT, scope)));
    p.put("uid", actor.id());
    String where =
        """
        EXISTS (SELECT 1 FROM homework_targets t WHERE t.homework_id = h.id AND t.group_id IN (:g))
        AND (h.hidden = 0 OR h.author_id = :uid OR EXISTS (
          SELECT 1 FROM homework_targets t2 WHERE t2.homework_id = h.id AND t2.group_id IN (:mod)))
        """;
    if (ids == null) {
      p.put("since", since);
      where += " AND h.due_at >= :since";
    } else {
      p.put("ids", List.copyOf(ids));
      where += " AND h.id IN (:ids)";
    }
    return views(actor, query(where + " ORDER BY h.id", p));
  }

  public Item get(Actor actor, long id) {
    Row r = row(actor, id);
    List<Long> t = targets.of(Targets.Kind.HOMEWORK, id);
    access.requireSee(actor, t);
    if (r.hidden() && !isAuthor(actor, r) && !canModerate(actor, t)) {
      throw ApiException.notFound();
    }
    return views(actor, List.of(r)).getFirst();
  }

  private Row row(Actor actor, long id) {
    List<Row> rows = query("h.id = :id", Map.of("id", id, "uid", actor.id()));
    if (rows.isEmpty()) {
      throw ApiException.notFound();
    }
    return rows.getFirst();
  }

  @Transactional
  public Item create(Actor actor, Input in) {
    if (in.subjectId() == null) {
      throw ApiException.invalid("subjectId", "Выберите предмет");
    }
    String title = NewsService.title(in.title());
    String md = NewsService.body(in.body());
    long due = due(in.dueAt());
    Integer difficulty = difficulty(in.difficulty());
    Set<Long> to =
        audience.resolve(actor, in.subjectId(), in.groupIds(), Permission.PUBLISH_HOMEWORK);
    long now = clock.millis();
    long id =
        db.sql(
                """
                INSERT INTO homework (subject_id, author_id, title, body_md, body_html, due_at,
                                      difficulty, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """)
            .params(
                in.subjectId(),
                actor.id(),
                title,
                md,
                Markdown.render(md),
                due,
                difficulty,
                now,
                now)
            .query(Long.class)
            .single();
    targets.set(Targets.Kind.HOMEWORK, id, to);
    setAttachments(actor, id, in.attachments());
    audit.log(actor, to.iterator().next(), "homework.create", "homework", id);
    Item item = get(actor, id);
    events.publishEvent(new Published(id, to, actor.id(), title, due, item.subject().name()));
    return item;
  }

  @Transactional
  public Item update(Actor actor, long id, Input in) {
    Row r = row(actor, id);
    List<Long> t = targets.of(Targets.Kind.HOMEWORK, id);
    access.requireSee(actor, t);
    if (!canEdit(actor, r, t)) {
      throw ApiException.forbidden();
    }
    String title = in.title() == null ? r.title() : NewsService.title(in.title());
    String md = in.body() == null ? r.bodyMd() : NewsService.body(in.body());
    long due = in.dueAt() == null ? r.dueAt() : due(in.dueAt());
    Integer difficulty = in.difficulty() == null ? r.difficulty() : difficulty(in.difficulty());
    db.sql(
            "UPDATE homework SET title = ?, body_md = ?, body_html = ?, due_at = ?,"
                + " difficulty = ?, updated_at = ? WHERE id = ?")
        .params(title, md, Markdown.render(md), due, difficulty, clock.millis(), id)
        .update();
    if (in.groupIds() != null && !in.groupIds().isEmpty()) {
      Set<Long> to =
          audience.resolve(actor, r.subjectId(), in.groupIds(), Permission.PUBLISH_HOMEWORK);
      targets.set(Targets.Kind.HOMEWORK, id, to);
    }
    if (in.attachments() != null) {
      setAttachments(actor, id, in.attachments());
    }
    return get(actor, id);
  }

  @Transactional
  public void delete(Actor actor, long id) {
    Row r = row(actor, id);
    List<Long> t = targets.of(Targets.Kind.HOMEWORK, id);
    access.requireSee(actor, t);
    if (!isAuthor(actor, r) && !canModerate(actor, t)) {
      throw ApiException.forbidden();
    }
    db.sql("DELETE FROM comments WHERE target_type = 'homework' AND target_id = ?")
        .param(id)
        .update();
    db.sql("DELETE FROM homework WHERE id = ?").param(id).update();
    audit.log(
        actor,
        t.isEmpty() ? null : t.getFirst(),
        "homework.delete",
        "homework",
        id,
        Map.of("title", r.title()));
  }

  @Transactional
  public void setHidden(Actor actor, long id, boolean hidden) {
    row(actor, id);
    List<Long> t = targets.of(Targets.Kind.HOMEWORK, id);
    access.requireSee(actor, t);
    if (!canModerate(actor, t)) {
      throw ApiException.forbidden();
    }
    db.sql("UPDATE homework SET hidden = ? WHERE id = ?").params(hidden ? 1 : 0, id).update();
    audit.log(actor, t.getFirst(), hidden ? "homework.hide" : "homework.unhide", "homework", id);
  }

  /** Личная отметка: видна только самому пользователю. */
  public void setDone(Actor actor, long id, boolean done) {
    get(actor, id);
    if (done) {
      db.sql(
              "INSERT INTO homework_done (user_id, homework_id, done_at) VALUES (?, ?, ?)"
                  + " ON CONFLICT DO NOTHING")
          .params(actor.id(), id, clock.millis())
          .update();
    } else {
      db.sql("DELETE FROM homework_done WHERE user_id = ? AND homework_id = ?")
          .params(actor.id(), id)
          .update();
    }
  }

  private boolean isAuthor(Actor actor, Row r) {
    return r.authorId() != null && r.authorId() == actor.id();
  }

  private boolean canEdit(Actor actor, Row r, List<Long> t) {
    return (isAuthor(actor, r) && access.can(actor, Permission.PUBLISH_HOMEWORK, t))
        || canModerate(actor, t);
  }

  private boolean canModerate(Actor actor, List<Long> t) {
    return access.can(actor, Permission.MODERATE_CONTENT, t);
  }

  /** 1–3 — сложность, 0 или null — не указана. */
  static Integer difficulty(Integer raw) {
    if (raw == null || raw == 0) {
      return null;
    }
    if (raw < 1 || raw > 3) {
      throw ApiException.invalid("difficulty", "Сложность: 1 — легко, 2 — средне, 3 — сложно");
    }
    return raw;
  }

  private long due(Long dueAt) {
    if (dueAt == null) {
      throw ApiException.invalid("dueAt", "Укажите дедлайн");
    }
    long now = clock.millis();
    if (dueAt < now - 365 * DAY || dueAt > now + 2 * 365 * DAY) {
      throw ApiException.invalid("dueAt", "Дедлайн вне разумного диапазона");
    }
    return dueAt;
  }

  private List<Item> views(Actor actor, List<Row> rows) {
    if (rows.isEmpty()) {
      return List.of();
    }
    List<Long> ids = rows.stream().map(Row::id).toList();
    Map<Long, List<Long>> t = targets.of(Targets.Kind.HOMEWORK, ids);
    Map<Long, List<MaterialService.FileInfo>> files = attachments(ids);
    Set<Long> authorIds = new HashSet<>();
    Set<Long> groupIds = new HashSet<>();
    for (Row r : rows) {
      if (r.authorId() != null) {
        authorIds.add(r.authorId());
      }
      groupIds.addAll(t.getOrDefault(r.id(), List.of()));
    }
    Map<Long, Person> authors = people.load(authorIds);
    Map<Long, String> groupNames = new HashMap<>();
    groups.listByIds(groupIds).forEach(g -> groupNames.put(g.id(), g.name()));
    List<Item> out = new ArrayList<>();
    for (Row r : rows) {
      List<Long> tg = t.getOrDefault(r.id(), List.of());
      boolean mod = canModerate(actor, tg);
      out.add(
          new Item(
              r.id(),
              r.title(),
              r.bodyMd(),
              r.bodyHtml(),
              r.dueAt(),
              r.difficulty(),
              r.done(),
              r.hidden(),
              r.createdAt(),
              r.updatedAt(),
              people.get(authors, r.authorId()),
              new NewsService.SubjectRef(r.subjectId(), r.subjectName(), r.subjectColor()),
              tg.stream()
                  .map(g -> new SubjectStore.GroupRef(g, groupNames.getOrDefault(g, "")))
                  .toList(),
              r.comments(),
              files.getOrDefault(r.id(), List.of()),
              new Can(canEdit(actor, r, tg), isAuthor(actor, r) || mod, mod)));
    }
    return out;
  }

  /**
   * Прикрепляет файлы к заданию. Новые файлы должен был загрузить сам автор; уже прикреплённые к
   * этому заданию остаются. Открепленные файлы удаляет фоновая уборка сирот.
   */
  private void setAttachments(Actor actor, long id, List<Long> fileIds) {
    List<Long> wanted = fileIds == null ? List.of() : fileIds.stream().distinct().toList();
    if (wanted.size() > 10) {
      throw ApiException.invalid("attachments", "Не больше 10 вложений");
    }
    List<Long> current =
        attachments(List.of(id)).getOrDefault(id, List.of()).stream()
            .map(MaterialService.FileInfo::id)
            .toList();
    for (Long f : wanted) {
      if (current.contains(f)) {
        continue;
      }
      Long uploader =
          db.sql("SELECT uploaded_by FROM files WHERE id = ?")
              .param(f)
              .query(Long.class)
              .optional()
              .orElseThrow(ApiException::notFound);
      boolean used =
          db.sql(
                      "SELECT (SELECT count(*) FROM materials WHERE file_id = ?)"
                          + " + (SELECT count(*) FROM homework_attachments WHERE file_id = ?)")
                  .params(f, f)
                  .query(Integer.class)
                  .single()
              > 0;
      if (uploader == null || uploader != actor.id() || used) {
        throw ApiException.forbidden("Этот файл нельзя прикрепить");
      }
    }
    db.sql("DELETE FROM homework_attachments WHERE homework_id = ?").param(id).update();
    for (int i = 0; i < wanted.size(); i++) {
      db.sql("INSERT INTO homework_attachments (homework_id, file_id, position) VALUES (?, ?, ?)")
          .params(id, wanted.get(i), i)
          .update();
    }
  }

  private Map<Long, List<MaterialService.FileInfo>> attachments(List<Long> homeworkIds) {
    Map<Long, List<MaterialService.FileInfo>> out = new HashMap<>();
    if (homeworkIds.isEmpty()) {
      return out;
    }
    db.sql(
            """
            SELECT a.homework_id, f.id, f.name, f.mime, f.size FROM homework_attachments a
            JOIN files f ON f.id = a.file_id
            WHERE a.homework_id IN (:ids) ORDER BY a.position
            """)
        .param("ids", homeworkIds)
        .query(
            rs -> {
              out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                  .add(
                      new MaterialService.FileInfo(
                          rs.getLong(2), rs.getString(3), rs.getString(4), rs.getLong(5)));
            });
    return out;
  }
}
