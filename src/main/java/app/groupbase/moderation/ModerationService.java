package app.groupbase.moderation;

import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Permission;
import app.groupbase.content.Access;
import app.groupbase.content.CommentService;
import app.groupbase.content.HomeworkService;
import app.groupbase.content.MaterialService;
import app.groupbase.content.NewsService;
import app.groupbase.content.SubjectStore;
import app.groupbase.content.Targets;
import app.groupbase.store.AuditStore;
import app.groupbase.store.People;
import app.groupbase.store.Person;
import app.groupbase.store.Rows;
import app.groupbase.web.ApiException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Модерация: жалобы участников, свежие и скрытые записи и комментарии, журнал модераторов — по
 * группам, где у человека есть право модерировать (администратор и модератор — во всех группах,
 * староста — в своей). Скрывают и удаляют сервисы новостей, заданий, материалов и комментариев — со
 * своими проверками прав и записью в журнал.
 */
@Service
public class ModerationService {

  /**
   * Новость, задание, материал или комментарий — для карточки в «Модерации».
   *
   * @param title заголовок; у комментария — заголовок того, к чему он
   * @param text начало текста одной строкой
   * @param href где это открыть (у комментария — страница, к которой он)
   * @param parentType у комментария — к чему он: post, homework, material
   */
  public record Target(
      String type,
      long id,
      String title,
      String text,
      Person author,
      long createdAt,
      boolean hidden,
      String href,
      List<Long> groups,
      String parentType) {}

  /**
   * Жалобы на одно и то же, вместе.
   *
   * @param reasons пояснения (без имён: кто пожаловался, модераторы не видят)
   */
  public record Report(Target target, int count, List<String> reasons, long lastAt) {}

  /** Сколько ждёт модератора: материалы на проверке и открытые жалобы. */
  public record Summary(int pending, int reports) {}

  /**
   * Запись журнала модерации.
   *
   * @param title что это было: заголовок или начало комментария
   */
  public record LogEntry(
      long id,
      long at,
      String actorName,
      String action,
      String targetType,
      Long targetId,
      String title) {}

  /** Жалоба отправлена — модераторам этих групп уведомление. */
  public record Reported(
      String type, long id, List<Long> groups, String title, String reason, long reporterId) {}

  static final Set<String> TYPES = Set.of("post", "homework", "material", "comment");

  /** Действия, которые показывает журнал модерации. */
  static final List<String> LOG_ACTIONS =
      List.of(
          "news.hide",
          "news.unhide",
          "news.delete",
          "homework.hide",
          "homework.unhide",
          "homework.delete",
          "material.approve",
          "material.reject",
          "material.hide",
          "material.unhide",
          "material.delete",
          "comment.hide",
          "comment.unhide",
          "comment.delete",
          "report.dismiss");

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int REASON_MAX = 500;

  private final JdbcClient db;
  private final Access access;
  private final Targets targets;
  private final SubjectStore subjects;
  private final People people;
  private final NewsService news;
  private final HomeworkService homework;
  private final MaterialService materials;
  private final CommentService comments;
  private final AuditService audit;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  public ModerationService(
      JdbcClient db,
      Access access,
      Targets targets,
      SubjectStore subjects,
      People people,
      NewsService news,
      HomeworkService homework,
      MaterialService materials,
      CommentService comments,
      AuditService audit,
      ApplicationEventPublisher events,
      Clock clock) {
    this.db = db;
    this.access = access;
    this.targets = targets;
    this.subjects = subjects;
    this.people = people;
    this.news = news;
    this.homework = homework;
    this.materials = materials;
    this.comments = comments;
    this.audit = audit;
    this.events = events;
    this.clock = clock;
  }

  // ---------- что модерирует человек ----------

  /** Группы, где у человека есть право модерировать; нет таких — нет и раздела. */
  Set<Long> moderated(Actor actor) {
    Set<Long> g =
        access.groupsWith(actor, Permission.MODERATE_CONTENT, access.visibleGroups(actor));
    if (g.isEmpty()) {
      throw ApiException.forbidden();
    }
    return g;
  }

  public Summary summary(Actor actor) {
    Set<Long> g = moderated(actor);
    int pending =
        db.sql(
                """
                SELECT COUNT(*) FROM materials m WHERE m.status = 'pending' AND EXISTS (
                  SELECT 1 FROM subject_groups sg
                  WHERE sg.subject_id = m.subject_id AND sg.group_id IN (:g))
                """)
            .param("g", List.copyOf(g))
            .query(Integer.class)
            .single();
    return new Summary(pending, open(g).size());
  }

  // ---------- жалобы ----------

  /** Пожаловаться на то, что видно: модераторы увидят жалобу и пояснение, но не имя. */
  public void report(Actor actor, String type, long id, String reason) {
    requireType(type);
    Raw r = raw(type, id).orElseThrow(ApiException::notFound);
    access.requireSee(actor, r.groups());
    if (r.authorId() != null && r.authorId() == actor.id()) {
      throw ApiException.badRequest("Своё можно изменить или удалить — жаловаться не нужно");
    }
    String why = reason == null ? "" : reason.strip();
    if (why.length() > REASON_MAX) {
      throw ApiException.invalid("reason", "Пояснение — не длиннее " + REASON_MAX + " символов");
    }
    int added =
        db.sql(
                "INSERT OR IGNORE INTO reports (target_type, target_id, reporter_id, reason,"
                    + " created_at) VALUES (?, ?, ?, ?, ?)")
            .params(type, id, actor.id(), why, clock.millis())
            .update();
    if (added > 0) {
      events.publishEvent(new Reported(type, id, r.groups(), what(r), why, actor.id()));
    }
  }

  public List<Report> reports(Actor actor) {
    List<Found> found = open(moderated(actor));
    Map<Long, Person> authors = authors(found.stream().map(Found::raw).toList());
    return found.stream()
        .map(f -> new Report(view(f.raw(), authors), f.count(), f.reasons(), f.lastAt()))
        .toList();
  }

  /**
   * Разобрать жалобы: скрыть, удалить или оставить как есть. Все открытые жалобы на это
   * закрываются.
   */
  @Transactional
  public void resolve(Actor actor, String type, long id, String action) {
    requireType(type);
    Optional<Raw> found = raw(type, id);
    if (found.isEmpty()) {
      close(type, id, actor.id(), "deleted");
      return;
    }
    List<Long> groups = found.get().groups();
    if (!access.can(actor, Permission.MODERATE_CONTENT, groups)) {
      throw ApiException.forbidden();
    }
    String resolution =
        switch (action == null ? "" : action) {
          case "hide" -> {
            hide(actor, type, id);
            yield "hidden";
          }
          case "delete" -> {
            delete(actor, type, id);
            yield "deleted";
          }
          case "dismiss" -> {
            audit.log(actor, groups.getFirst(), "report.dismiss", type, id);
            yield "dismissed";
          }
          default -> throw ApiException.badRequest("Неизвестное действие");
        };
    close(type, id, actor.id(), resolution);
  }

  private void hide(Actor actor, String type, long id) {
    switch (type) {
      case "post" -> news.setHidden(actor, id, true);
      case "homework" -> homework.setHidden(actor, id, true);
      case "material" -> materials.moderate(actor, id, "hide");
      default -> comments.setHidden(actor, id, true);
    }
  }

  private void delete(Actor actor, String type, long id) {
    switch (type) {
      case "post" -> news.delete(actor, id);
      case "homework" -> homework.delete(actor, id);
      case "material" -> materials.delete(actor, id);
      default -> comments.delete(actor, id);
    }
  }

  private void close(String type, long id, Long by, String resolution) {
    db.sql(
            "UPDATE reports SET resolved_at = ?, resolved_by = ?, resolution = ?"
                + " WHERE target_type = ? AND target_id = ? AND resolved_at IS NULL")
        .params(clock.millis(), by, resolution, type, id)
        .update();
  }

  private record Found(Raw raw, int count, List<String> reasons, long lastAt) {}

  /**
   * Открытые жалобы на то, что человек модерирует, новые сначала. Жалобы на удалённое и уже скрытое
   * закрываются сами: разбирать там нечего.
   */
  private List<Found> open(Set<Long> g) {
    record Open(String type, long id, int count, long lastAt, String reasons) {}
    List<Open> open =
        db.sql(
                """
                SELECT target_type, target_id, COUNT(*), MAX(created_at), json_group_array(reason)
                FROM reports WHERE resolved_at IS NULL
                GROUP BY target_type, target_id ORDER BY MAX(created_at) DESC
                """)
            .query(
                (rs, i) ->
                    new Open(
                        rs.getString(1),
                        rs.getLong(2),
                        rs.getInt(3),
                        rs.getLong(4),
                        rs.getString(5)))
            .list();
    List<Found> out = new ArrayList<>();
    for (Open o : open) {
      Optional<Raw> r = raw(o.type(), o.id());
      if (r.isEmpty() || r.get().hidden()) {
        close(o.type(), o.id(), null, r.isEmpty() ? "deleted" : "hidden");
        continue;
      }
      if (!Collections.disjoint(r.get().groups(), g)) {
        out.add(new Found(r.get(), o.count(), reasons(o.reasons()), o.lastAt()));
      }
    }
    return out;
  }

  private static List<String> reasons(String json) {
    List<String> out = new ArrayList<>();
    for (JsonNode n : JSON.readTree(json)) {
      String s = n.asString("").strip();
      if (!s.isEmpty() && !out.contains(s)) {
        out.add(s);
      }
    }
    return out;
  }

  // ---------- комментарии и скрытое ----------

  /** Условие «запись видна одной из групп :g» для новости, задания и материала. */
  private static final String IN_GROUPS =
      """
      ((%1$s = 'post' AND EXISTS (SELECT 1 FROM post_targets t
          WHERE t.post_id = %2$s AND t.group_id IN (:g)))
       OR (%1$s = 'homework' AND EXISTS (SELECT 1 FROM homework_targets t
          WHERE t.homework_id = %2$s AND t.group_id IN (:g)))
       OR (%1$s = 'material' AND EXISTS (SELECT 1 FROM materials m
          JOIN subject_groups sg ON sg.subject_id = m.subject_id
          WHERE m.id = %2$s AND sg.group_id IN (:g))))
      """;

  /** Свежие комментарии, новые сначала, со скрытыми; before — курсор по id. */
  public List<Target> comments(Actor actor, Long before) {
    Set<Long> g = moderated(actor);
    List<Long> ids =
        db.sql(
                "SELECT c.id FROM comments c WHERE (:before IS NULL OR c.id < :before) AND "
                    + IN_GROUPS.formatted("c.target_type", "c.target_id")
                    + " ORDER BY c.id DESC LIMIT 30")
            .param("before", before)
            .param("g", List.copyOf(g))
            .query(Long.class)
            .list();
    return views(ids.stream().flatMap(id -> comment(id).stream()).toList());
  }

  /** Скрытое модераторами: вернуть или удалить насовсем. */
  public List<Target> hidden(Actor actor) {
    Map<String, Object> g = Map.of("g", List.copyOf(moderated(actor)));
    List<Raw> out = new ArrayList<>();
    for (String type : List.of("post", "homework", "material")) {
      String table =
          switch (type) {
            case "post" -> "posts";
            case "homework" -> "homework";
            default -> "materials";
          };
      String published = type.equals("material") ? " AND x.status = 'published'" : "";
      db.sql(
              "SELECT x.id FROM %s x WHERE x.hidden = 1%s AND %s ORDER BY x.id DESC LIMIT 100"
                  .formatted(table, published, IN_GROUPS.formatted("'" + type + "'", "x.id")))
          .params(g)
          .query(Long.class)
          .list()
          .forEach(id -> raw(type, id).ifPresent(out::add));
    }
    db.sql(
            "SELECT c.id FROM comments c WHERE c.hidden = 1 AND "
                + IN_GROUPS.formatted("c.target_type", "c.target_id")
                + " ORDER BY c.id DESC LIMIT 100")
        .params(g)
        .query(Long.class)
        .list()
        .forEach(id -> comment(id).ifPresent(out::add));
    out.sort(Comparator.comparingLong(Raw::createdAt).reversed());
    return views(out);
  }

  // ---------- журнал ----------

  /**
   * Кто что скрыл, вернул, удалил, одобрил. Администратор и модератор видят весь сайт, староста —
   * свои группы (нужно и право видеть журнал).
   */
  public List<LogEntry> log(Actor actor, Long before) {
    Set<Long> g = moderated(actor);
    List<Long> scope = null;
    if (actor.instanceRole() == null) {
      scope = List.copyOf(access.groupsWith(actor, Permission.VIEW_AUDIT, g));
      if (scope.isEmpty()) {
        throw ApiException.forbidden();
      }
    } else if (!access.can(actor, Permission.VIEW_AUDIT, g)) {
      throw ApiException.forbidden();
    }
    return audit.list(scope, LOG_ACTIONS, before, 50).stream().map(this::entry).toList();
  }

  private LogEntry entry(AuditStore.Entry e) {
    String title = null;
    if (e.details() != null) {
      JsonNode d = JSON.readTree(e.details());
      title = d.path("title").asString(d.path("text").asString(null));
    }
    if (title == null && e.targetType() != null && e.targetId() != null) {
      title =
          raw(e.targetType(), e.targetId())
              .map(
                  r ->
                      r.type().equals("comment")
                          ? CommentService.snippet(r.text(), 120)
                          : r.title())
              .orElse(null);
    }
    return new LogEntry(
        e.id(), e.at(), e.actorName(), e.action(), e.targetType(), e.targetId(), title);
  }

  // ---------- записи ----------

  /** Запись из базы, из которой строится карточка. */
  private record Raw(
      String type,
      long id,
      String title,
      String text,
      Long authorId,
      long createdAt,
      boolean hidden,
      String href,
      List<Long> groups,
      String parentType) {}

  /** Поля, общие для новости, задания и материала. */
  private record Fields(String title, String text, Long authorId, long createdAt, boolean hidden) {
    Raw as(String type, long id, String href, List<Long> groups) {
      return new Raw(type, id, title, text, authorId, createdAt, hidden, href, groups, null);
    }
  }

  private Optional<Fields> fields(String sql, long id) {
    return db.sql(sql)
        .param(id)
        .query(
            (rs, i) ->
                new Fields(
                    rs.getString(1),
                    rs.getString(2),
                    Rows.longOrNull(rs, "author_id"),
                    rs.getLong(4),
                    rs.getInt(5) != 0))
        .optional();
  }

  private Optional<Raw> raw(String type, long id) {
    return switch (type) {
      case "post" ->
          fields("SELECT title, body_md, author_id, created_at, hidden FROM posts WHERE id = ?", id)
              .map(f -> f.as(type, id, "/news/" + id, targets.of(Targets.Kind.POST, id)));
      case "homework" ->
          fields(
                  "SELECT title, body_md, author_id, created_at, hidden FROM homework WHERE id = ?",
                  id)
              .map(f -> f.as(type, id, "/homework/" + id, targets.of(Targets.Kind.HOMEWORK, id)));
      case "material" ->
          db.sql("SELECT subject_id FROM materials WHERE id = ?")
              .param(id)
              .query(Long.class)
              .optional()
              .flatMap(
                  subject ->
                      fields(
                              "SELECT title, description, author_id, created_at, hidden"
                                  + " FROM materials WHERE id = ?",
                              id)
                          .map(
                              f -> f.as(type, id, "/materials/" + id, subjects.groupIds(subject))));
      case "comment" -> comment(id);
      default -> Optional.empty();
    };
  }

  /** Комментарий: заголовок, адрес и группы — от того, к чему он. */
  private Optional<Raw> comment(long id) {
    record C(
        String parentType, long parentId, String text, Long authorId, long at, boolean hidden) {}
    return db.sql(
            "SELECT target_type, target_id, body_md, author_id, created_at, hidden"
                + " FROM comments WHERE id = ?")
        .param(id)
        .query(
            (rs, i) ->
                new C(
                    rs.getString(1),
                    rs.getLong(2),
                    rs.getString(3),
                    Rows.longOrNull(rs, "author_id"),
                    rs.getLong(5),
                    rs.getInt(6) != 0))
        .optional()
        .flatMap(
            c ->
                raw(c.parentType(), c.parentId())
                    .map(
                        p ->
                            new Raw(
                                "comment",
                                id,
                                p.title(),
                                c.text(),
                                c.authorId(),
                                c.at(),
                                c.hidden(),
                                p.href(),
                                p.groups(),
                                p.type())));
  }

  /** «новость «…»», «комментарий к заданию «…»» — для уведомления модераторам. */
  private static String what(Raw r) {
    return switch (r.type()) {
      case "post" -> "новость «" + r.title() + "»";
      case "homework" -> "задание «" + r.title() + "»";
      case "material" -> "материал «" + r.title() + "»";
      default -> "комментарий к «" + r.title() + "»";
    };
  }

  private static void requireType(String type) {
    if (type == null || !TYPES.contains(type)) {
      throw ApiException.badRequest(
          "Жаловаться можно на новость, задание, материал или комментарий");
    }
  }

  private Map<Long, Person> authors(List<Raw> rows) {
    Set<Long> ids = new HashSet<>();
    for (Raw r : rows) {
      if (r.authorId() != null) {
        ids.add(r.authorId());
      }
    }
    return people.load(ids);
  }

  private List<Target> views(List<Raw> rows) {
    Map<Long, Person> authors = authors(rows);
    return rows.stream().map(r -> view(r, authors)).toList();
  }

  private Target view(Raw r, Map<Long, Person> authors) {
    int max = r.type().equals("comment") ? 600 : 280;
    return new Target(
        r.type(),
        r.id(),
        r.title(),
        CommentService.snippet(r.text(), max),
        people.get(authors, r.authorId()),
        r.createdAt(),
        r.hidden(),
        r.href(),
        r.groups(),
        r.parentType());
  }
}
