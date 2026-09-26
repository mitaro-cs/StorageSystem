package app.groupbase.content;

import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Permission;
import app.groupbase.store.People;
import app.groupbase.store.Person;
import app.groupbase.store.Rows;
import app.groupbase.web.ApiException;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Комментарии к новостям, ДЗ и материалам. Права — по группам родительской записи. */
@Service
public class CommentService {

  public enum Parent {
    POST,
    HOMEWORK,
    MATERIAL;

    public String id() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * @param canHide скрыть и вернуть может модератор: скрытый видят только модераторы и автор
   */
  public record Comment(
      long id,
      Person author,
      String bodyHtml,
      long createdAt,
      boolean hidden,
      boolean canDelete,
      boolean canHide) {}

  private record Row(long id, Long authorId, String bodyHtml, long createdAt, boolean hidden) {}

  private final JdbcClient db;
  private final Targets targets;
  private final SubjectStore subjects;
  private final Access access;
  private final People people;
  private final AuditService audit;
  private final Clock clock;

  public CommentService(
      JdbcClient db,
      Targets targets,
      SubjectStore subjects,
      Access access,
      People people,
      AuditService audit,
      Clock clock) {
    this.db = db;
    this.targets = targets;
    this.subjects = subjects;
    this.access = access;
    this.people = people;
    this.audit = audit;
    this.clock = clock;
  }

  /** Группы, которым виден родитель. Пусто — родителя нет. */
  public List<Long> parentGroups(Parent parent, long id) {
    return switch (parent) {
      case POST -> targets.of(Targets.Kind.POST, id);
      case HOMEWORK -> targets.of(Targets.Kind.HOMEWORK, id);
      case MATERIAL ->
          db.sql("SELECT subject_id FROM materials WHERE id = ?")
              .param(id)
              .query(Long.class)
              .optional()
              .map(subjects::groupIds)
              .orElse(List.of());
    };
  }

  private List<Long> visibleParent(Actor actor, Parent parent, long id) {
    List<Long> groups = parentGroups(parent, id);
    if (groups.isEmpty()) {
      throw ApiException.notFound();
    }
    access.requireSee(actor, groups);
    return groups;
  }

  public List<Comment> list(Actor actor, Parent parent, long id) {
    List<Long> groups = visibleParent(actor, parent, id);
    boolean mod = access.can(actor, Permission.MODERATE_CONTENT, groups);
    List<Row> rows =
        db.sql(
                "SELECT id, author_id, body_html, created_at, hidden FROM comments"
                    + " WHERE target_type = ? AND target_id = ? AND (hidden = 0 OR ? OR author_id = ?)"
                    + " ORDER BY id")
            .params(parent.id(), id, mod ? 1 : 0, actor.id())
            .query(
                (rs, i) ->
                    new Row(
                        rs.getLong(1),
                        Rows.longOrNull(rs, "author_id"),
                        rs.getString(3),
                        rs.getLong(4),
                        Rows.bool(rs, "hidden")))
            .list();
    Set<Long> authorIds = new HashSet<>();
    rows.forEach(
        r -> {
          if (r.authorId() != null) {
            authorIds.add(r.authorId());
          }
        });
    Map<Long, Person> authors = people.load(authorIds);
    return rows.stream()
        .map(
            r ->
                new Comment(
                    r.id(),
                    people.get(authors, r.authorId()),
                    r.bodyHtml(),
                    r.createdAt(),
                    r.hidden(),
                    mod || (r.authorId() != null && r.authorId() == actor.id()),
                    mod))
        .toList();
  }

  public Comment add(Actor actor, Parent parent, long id, String body) {
    List<Long> groups = visibleParent(actor, parent, id);
    if (!access.can(actor, Permission.COMMENT, groups)) {
      throw ApiException.forbidden();
    }
    String md = body == null ? "" : body.strip();
    if (md.isEmpty() || md.length() > 4000) {
      throw ApiException.invalid("body", "Комментарий: от 1 до 4000 символов");
    }
    long now = clock.millis();
    String html = Markdown.render(md);
    long cid =
        db.sql(
                "INSERT INTO comments (target_type, target_id, author_id, body_md, body_html,"
                    + " created_at) VALUES (?, ?, ?, ?, ?, ?) RETURNING id")
            .params(parent.id(), id, actor.id(), md, html, now)
            .query(Long.class)
            .single();
    Person me = people.load(List.of(actor.id())).get(actor.id());
    return new Comment(
        cid, me, html, now, false, true, access.can(actor, Permission.MODERATE_CONTENT, groups));
  }

  private record C(String type, long targetId, Long authorId) {}

  private C find(long commentId) {
    return db.sql("SELECT target_type, target_id, author_id FROM comments WHERE id = ?")
        .param(commentId)
        .query((rs, i) -> new C(rs.getString(1), rs.getLong(2), Rows.longOrNull(rs, "author_id")))
        .optional()
        .orElseThrow(ApiException::notFound);
  }

  /**
   * Скрыть или вернуть комментарий — модератор. Скрытый не удаляется: его видят модераторы и автор,
   * а вернуть можно в «Модерации».
   */
  @Transactional
  public void setHidden(Actor actor, long commentId, boolean hidden) {
    C c = find(commentId);
    Parent parent = Parent.valueOf(c.type().toUpperCase(Locale.ROOT));
    List<Long> groups = visibleParent(actor, parent, c.targetId());
    if (!access.can(actor, Permission.MODERATE_CONTENT, groups)) {
      throw ApiException.forbidden();
    }
    db.sql("UPDATE comments SET hidden = ? WHERE id = ?")
        .params(hidden ? 1 : 0, commentId)
        .update();
    audit.log(
        actor, groups.getFirst(), hidden ? "comment.hide" : "comment.unhide", "comment", commentId);
  }

  public void delete(Actor actor, long commentId) {
    C c = find(commentId);
    Parent parent = Parent.valueOf(c.type().toUpperCase(Locale.ROOT));
    List<Long> groups = visibleParent(actor, parent, c.targetId());
    boolean own = c.authorId() != null && c.authorId() == actor.id();
    if (!own && !access.can(actor, Permission.MODERATE_CONTENT, groups)) {
      throw ApiException.forbidden();
    }
    String text =
        db.sql("SELECT body_md FROM comments WHERE id = ?")
            .param(commentId)
            .query(String.class)
            .optional()
            .orElse("");
    db.sql("DELETE FROM comments WHERE id = ?").param(commentId).update();
    if (!own) {
      // Самого комментария больше нет — в журнале модерации видно, что это было и где.
      audit.log(
          actor,
          groups.getFirst(),
          "comment.delete",
          "comment",
          commentId,
          Map.of("text", snippet(text, 120), "parent", parent.id() + ":" + c.targetId()));
    }
  }

  /** Начало текста одной строкой: для журнала и карточек модерации. */
  public static String snippet(String markdown, int max) {
    String s =
        markdown == null
            ? ""
            : markdown.replaceAll("[*_`#>\\[\\]]", "").replaceAll("\\s+", " ").strip();
    return s.length() <= max ? s : s.substring(0, max - 1).strip() + "…";
  }
}
