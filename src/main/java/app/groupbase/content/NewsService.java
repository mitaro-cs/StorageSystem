package app.groupbase.content;

import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Permission;
import app.groupbase.store.GroupStore;
import app.groupbase.store.People;
import app.groupbase.store.Person;
import app.groupbase.store.Rows;
import app.groupbase.web.ApiException;
import java.time.Clock;
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

/** Новости: Markdown, закрепление, срочность, адресация группам или общему предмету. */
@Service
public class NewsService {

  public record Input(
      String title,
      String body,
      List<Long> groupIds,
      Long subjectId,
      Boolean pinned,
      Boolean urgent,
      /* Фото и файлы: id загруженных файлов; null при изменении — не трогать. */
      List<Long> attachments) {
    public Input(
        String title,
        String body,
        List<Long> groupIds,
        Long subjectId,
        Boolean pinned,
        Boolean urgent) {
      this(title, body, groupIds, subjectId, pinned, urgent, null);
    }
  }

  public record SubjectRef(long id, String name, String color) {}

  public record Can(boolean edit, boolean delete, boolean hide) {}

  public record Item(
      long id,
      String title,
      String bodyMd,
      String bodyHtml,
      boolean pinned,
      boolean urgent,
      boolean hidden,
      long createdAt,
      long updatedAt,
      Person author,
      SubjectRef subject,
      List<SubjectStore.GroupRef> groups,
      int comments,
      List<MaterialService.FileInfo> attachments,
      Can can) {}

  public record Page(List<Item> pinned, List<Item> items, Long next) {}

  /** Событие для уведомлений и поиска. */
  public record Published(
      long id, Set<Long> groups, long authorId, boolean urgent, String title, Long subjectId) {}

  private record Row(
      long id,
      Long authorId,
      Long subjectId,
      String subjectName,
      String subjectColor,
      String title,
      String bodyMd,
      String bodyHtml,
      boolean pinned,
      boolean urgent,
      boolean hidden,
      long createdAt,
      long updatedAt,
      int comments) {}

  private final JdbcClient db;
  private final Targets targets;
  private final Audience audience;
  private final Access access;
  private final People people;
  private final GroupStore groups;
  private final AuditService audit;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  public NewsService(
      JdbcClient db,
      Targets targets,
      Audience audience,
      Access access,
      People people,
      GroupStore groups,
      AuditService audit,
      ApplicationEventPublisher events,
      Clock clock) {
    this.db = db;
    this.targets = targets;
    this.audience = audience;
    this.access = access;
    this.people = people;
    this.groups = groups;
    this.audit = audit;
    this.events = events;
    this.clock = clock;
  }

  private static final String SELECT =
      """
      SELECT p.*, s.name AS subject_name, s.color AS subject_color,
        (SELECT count(*) FROM comments c
          WHERE c.target_type = 'post' AND c.target_id = p.id AND c.hidden = 0) AS comment_count
      FROM posts p LEFT JOIN subjects s ON s.id = p.subject_id
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
                    Rows.longOrNull(rs, "author_id"),
                    Rows.longOrNull(rs, "subject_id"),
                    rs.getString("subject_name"),
                    rs.getString("subject_color"),
                    rs.getString("title"),
                    rs.getString("body_md"),
                    rs.getString("body_html"),
                    Rows.bool(rs, "pinned"),
                    Rows.bool(rs, "urgent"),
                    Rows.bool(rs, "hidden"),
                    rs.getLong("created_at"),
                    rs.getLong("updated_at"),
                    rs.getInt("comment_count")))
        .list();
  }

  /** Лента: закреплённые (только на первой странице) и остальное по курсору. */
  public Page feed(Actor actor, Long group, Long subject, Long before, int limit) {
    List<Long> scope = access.scope(actor, group);
    if (scope.isEmpty()) {
      return new Page(List.of(), List.of(), null);
    }
    Set<Long> mod = access.groupsWith(actor, Permission.MODERATE_CONTENT, scope);
    Map<String, Object> p = new HashMap<>();
    p.put("g", scope);
    p.put("mod", Targets.nonEmpty(mod));
    p.put("uid", actor.id());
    p.put("subject", subject);
    String base =
        """
        EXISTS (SELECT 1 FROM post_targets t WHERE t.post_id = p.id AND t.group_id IN (:g))
        AND (:subject IS NULL OR p.subject_id = :subject)
        AND (p.hidden = 0 OR p.author_id = :uid OR EXISTS (
          SELECT 1 FROM post_targets t2 WHERE t2.post_id = p.id AND t2.group_id IN (:mod)))
        AND (:subject IS NOT NULL OR p.subject_id IS NULL OR NOT EXISTS (
          SELECT 1 FROM subject_hidden sh WHERE sh.user_id = :uid AND sh.subject_id = p.subject_id))
        """;
    List<Row> pinned = List.of();
    if (before == null) {
      pinned = query(base + " AND p.pinned = 1 ORDER BY p.id DESC LIMIT 10", p);
    }
    p.put("before", before);
    p.put("limit", limit + 1);
    List<Row> rows =
        query(
            base
                + " AND p.pinned = 0 AND (:before IS NULL OR p.id < :before)"
                + " ORDER BY p.id DESC LIMIT :limit",
            p);
    Long next = null;
    if (rows.size() > limit) {
      rows = rows.subList(0, limit);
      next = rows.getLast().id();
    }
    return new Page(views(actor, pinned), views(actor, rows), next);
  }

  /**
   * Для офлайн-синхронизации: видимые новости среди {@code ids} или, если ids == null, все видимые
   * новее {@code since}.
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
    p.put("subject", null);
    String where =
        """
        EXISTS (SELECT 1 FROM post_targets t WHERE t.post_id = p.id AND t.group_id IN (:g))
        AND (p.hidden = 0 OR p.author_id = :uid OR EXISTS (
          SELECT 1 FROM post_targets t2 WHERE t2.post_id = p.id AND t2.group_id IN (:mod)))
        """;
    if (ids == null) {
      p.put("since", since);
      where += " AND p.created_at >= :since";
    } else {
      p.put("ids", List.copyOf(ids));
      where += " AND p.id IN (:ids)";
    }
    return views(actor, query(where + " ORDER BY p.id", p));
  }

  public Item get(Actor actor, long id) {
    Row r = row(id);
    List<Long> t = targets.of(Targets.Kind.POST, id);
    access.requireSee(actor, t);
    if (r.hidden() && !isAuthor(actor, r) && !canModerate(actor, r, t)) {
      throw ApiException.notFound();
    }
    return views(actor, List.of(r)).getFirst();
  }

  private Row row(long id) {
    List<Row> rows = query("p.id = :id", Map.of("id", id));
    if (rows.isEmpty()) {
      throw ApiException.notFound();
    }
    return rows.getFirst();
  }

  @Transactional
  public Item create(Actor actor, Input in) {
    String title = title(in.title());
    String md = body(in.body());
    Set<Long> to = audience.resolve(actor, in.subjectId(), in.groupIds(), Permission.PUBLISH_NEWS);
    long now = clock.millis();
    long id =
        db.sql(
                """
                INSERT INTO posts (author_id, subject_id, title, body_md, body_html, pinned, urgent,
                                   created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """)
            .params(
                actor.id(),
                in.subjectId(),
                title,
                md,
                Markdown.render(md),
                Boolean.TRUE.equals(in.pinned()) ? 1 : 0,
                Boolean.TRUE.equals(in.urgent()) ? 1 : 0,
                now,
                now)
            .query(Long.class)
            .single();
    targets.set(Targets.Kind.POST, id, to);
    setAttachments(actor, id, in.attachments());
    audit.log(actor, to.iterator().next(), "news.create", "post", id, Map.of("title", title));
    events.publishEvent(
        new Published(id, to, actor.id(), Boolean.TRUE.equals(in.urgent()), title, in.subjectId()));
    return get(actor, id);
  }

  @Transactional
  public Item update(Actor actor, long id, Input in) {
    Row r = row(id);
    List<Long> t = targets.of(Targets.Kind.POST, id);
    access.requireSee(actor, t);
    if (!canEdit(actor, r, t)) {
      throw ApiException.forbidden();
    }
    String title = in.title() == null ? r.title() : title(in.title());
    String md = in.body() == null ? r.bodyMd() : body(in.body());
    boolean pinned = in.pinned() == null ? r.pinned() : in.pinned();
    boolean urgent = in.urgent() == null ? r.urgent() : in.urgent();
    db.sql(
            "UPDATE posts SET title = ?, body_md = ?, body_html = ?, pinned = ?, urgent = ?,"
                + " updated_at = ? WHERE id = ?")
        .params(title, md, Markdown.render(md), pinned ? 1 : 0, urgent ? 1 : 0, clock.millis(), id)
        .update();
    if (in.attachments() != null) {
      setAttachments(actor, id, in.attachments());
    }
    return get(actor, id);
  }

  /**
   * Прикрепляет фото и файлы к новости. Новые файлы должен был загрузить сам автор (или модератор,
   * который правит новость); уже прикреплённые к ней остаются. Открепленные удалит уборка сирот.
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
      if (uploader == null || uploader != actor.id() || MaterialService.used(db, f)) {
        throw ApiException.forbidden("Этот файл нельзя прикрепить");
      }
    }
    db.sql("DELETE FROM post_attachments WHERE post_id = ?").param(id).update();
    for (int i = 0; i < wanted.size(); i++) {
      db.sql("INSERT INTO post_attachments (post_id, file_id, position) VALUES (?, ?, ?)")
          .params(id, wanted.get(i), i)
          .update();
    }
  }

  private Map<Long, List<MaterialService.FileInfo>> attachments(List<Long> postIds) {
    Map<Long, List<MaterialService.FileInfo>> out = new HashMap<>();
    if (postIds.isEmpty()) {
      return out;
    }
    db.sql(
            """
            SELECT a.post_id, f.id, f.name, f.mime, f.size FROM post_attachments a
            JOIN files f ON f.id = a.file_id
            WHERE a.post_id IN (:ids) ORDER BY a.position
            """)
        .param("ids", postIds)
        .query(
            rs -> {
              out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                  .add(
                      new MaterialService.FileInfo(
                          rs.getLong(2), rs.getString(3), rs.getString(4), rs.getLong(5)));
            });
    return out;
  }

  @Transactional
  public void delete(Actor actor, long id) {
    Row r = row(id);
    List<Long> t = targets.of(Targets.Kind.POST, id);
    access.requireSee(actor, t);
    if (!isAuthor(actor, r) && !canModerate(actor, r, t)) {
      throw ApiException.forbidden();
    }
    db.sql("DELETE FROM comments WHERE target_type = 'post' AND target_id = ?").param(id).update();
    db.sql("DELETE FROM posts WHERE id = ?").param(id).update();
    audit.log(
        actor,
        t.isEmpty() ? null : t.getFirst(),
        "news.delete",
        "post",
        id,
        Map.of("title", r.title()));
  }

  @Transactional
  public void setHidden(Actor actor, long id, boolean hidden) {
    Row r = row(id);
    List<Long> t = targets.of(Targets.Kind.POST, id);
    access.requireSee(actor, t);
    if (!canModerate(actor, r, t)) {
      throw ApiException.forbidden();
    }
    db.sql("UPDATE posts SET hidden = ? WHERE id = ?").params(hidden ? 1 : 0, id).update();
    audit.log(actor, t.getFirst(), hidden ? "news.hide" : "news.unhide", "post", id);
  }

  private boolean isAuthor(Actor actor, Row r) {
    return r.authorId() != null && r.authorId() == actor.id();
  }

  private boolean canEdit(Actor actor, Row r, List<Long> t) {
    return (isAuthor(actor, r) && access.can(actor, Permission.PUBLISH_NEWS, t))
        || canModerate(actor, r, t);
  }

  private boolean canModerate(Actor actor, Row r, List<Long> t) {
    return access.can(actor, Permission.MODERATE_CONTENT, t);
  }

  private List<Item> views(Actor actor, List<Row> rows) {
    if (rows.isEmpty()) {
      return List.of();
    }
    List<Long> ids = rows.stream().map(Row::id).toList();
    Map<Long, List<Long>> t = targets.of(Targets.Kind.POST, ids);
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
    Map<Long, List<MaterialService.FileInfo>> files = attachments(ids);
    List<Item> out = new ArrayList<>();
    for (Row r : rows) {
      List<Long> tg = t.getOrDefault(r.id(), List.of());
      boolean mod = canModerate(actor, r, tg);
      out.add(
          new Item(
              r.id(),
              r.title(),
              r.bodyMd(),
              r.bodyHtml(),
              r.pinned(),
              r.urgent(),
              r.hidden(),
              r.createdAt(),
              r.updatedAt(),
              people.get(authors, r.authorId()),
              r.subjectId() == null
                  ? null
                  : new SubjectRef(r.subjectId(), r.subjectName(), r.subjectColor()),
              tg.stream()
                  .map(g -> new SubjectStore.GroupRef(g, groupNames.getOrDefault(g, "")))
                  .toList(),
              r.comments(),
              files.getOrDefault(r.id(), List.of()),
              new Can(canEdit(actor, r, tg), isAuthor(actor, r) || mod, mod)));
    }
    return out;
  }

  static String title(String raw) {
    String t = raw == null ? "" : raw.strip();
    if (t.isEmpty() || t.length() > 200) {
      throw ApiException.invalid("title", "Заголовок: от 1 до 200 символов");
    }
    return t;
  }

  static String body(String raw) {
    String b = raw == null ? "" : raw.strip();
    if (b.length() > Markdown.MAX_LENGTH) {
      throw ApiException.invalid("body", "Текст слишком длинный");
    }
    return b;
  }
}
