package app.groupbase.content;

import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Permission;
import app.groupbase.files.FileStore;
import app.groupbase.files.StoredFile;
import app.groupbase.store.People;
import app.groupbase.store.Person;
import app.groupbase.store.Rows;
import app.groupbase.web.ApiException;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Материалы предмета: файлы и ссылки в папках. Видны всем группам предмета. Кто может загружать
 * сразу — публикует; у кого есть только право предлагать (⚙ для студентов) — отправляет на
 * премодерацию.
 */
@Service
public class MaterialService {

  public record Input(
      String kind, String title, String description, String url, Long fileId, Long folderId) {}

  public record FolderInput(String name, Long parentId) {}

  public record FileInfo(long id, String name, String mime, long size) {}

  public record Can(boolean edit, boolean delete, boolean moderate) {}

  public record Item(
      long id,
      long subjectId,
      String subjectName,
      String subjectColor,
      Long folderId,
      String kind,
      String title,
      String description,
      String url,
      FileInfo file,
      Person author,
      String status,
      boolean hidden,
      long createdAt,
      int comments,
      Can can) {}

  public record Folder(long id, Long parentId, String name, int count) {}

  public record Crumb(Long id, String name) {}

  public record Listing(
      List<Crumb> path,
      List<Folder> folders,
      List<Item> materials,
      boolean canUpload,
      boolean canSuggest) {}

  public record Submitted(long id, long subjectId, String title, String status, long authorId) {}

  private record Row(
      long id,
      long subjectId,
      String subjectName,
      String subjectColor,
      Long folderId,
      String kind,
      String title,
      String description,
      String url,
      Long fileId,
      String fileName,
      String fileMime,
      Long fileSize,
      Long authorId,
      String status,
      boolean hidden,
      long createdAt,
      int comments) {}

  private final JdbcClient db;
  private final SubjectService subjects;
  private final SubjectStore subjectStore;
  private final Access access;
  private final People people;
  private final FileStore files;
  private final AuditService audit;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  public MaterialService(
      JdbcClient db,
      SubjectService subjects,
      SubjectStore subjectStore,
      Access access,
      People people,
      FileStore files,
      AuditService audit,
      ApplicationEventPublisher events,
      Clock clock) {
    this.db = db;
    this.subjects = subjects;
    this.subjectStore = subjectStore;
    this.access = access;
    this.people = people;
    this.files = files;
    this.audit = audit;
    this.events = events;
    this.clock = clock;
  }

  private static final String SELECT =
      """
      SELECT m.*, s.name AS subject_name, s.color AS subject_color,
        f.name AS file_name, f.mime AS file_mime, f.size AS file_size,
        (SELECT count(*) FROM comments c
          WHERE c.target_type = 'material' AND c.target_id = m.id AND c.hidden = 0) AS comment_count
      FROM materials m
      JOIN subjects s ON s.id = m.subject_id
      LEFT JOIN files f ON f.id = m.file_id
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
                    Rows.longOrNull(rs, "folder_id"),
                    rs.getString("kind"),
                    rs.getString("title"),
                    rs.getString("description"),
                    rs.getString("url"),
                    Rows.longOrNull(rs, "file_id"),
                    rs.getString("file_name"),
                    rs.getString("file_mime"),
                    Rows.longOrNull(rs, "file_size"),
                    Rows.longOrNull(rs, "author_id"),
                    rs.getString("status"),
                    Rows.bool(rs, "hidden"),
                    rs.getLong("created_at"),
                    rs.getInt("comment_count")))
        .list();
  }

  // --- просмотр ---

  /** Содержимое папки предмета (folderId == null — корень). */
  public Listing list(Actor actor, long subjectId, Long folderId) {
    subjects.visible(actor, subjectId);
    List<Long> groups = subjectStore.groupIds(subjectId);
    if (folderId != null) {
      requireFolder(subjectId, folderId);
    }
    boolean mod = access.can(actor, Permission.MODERATE_CONTENT, groups);
    Map<String, Object> p = new HashMap<>();
    p.put("s", subjectId);
    p.put("f", folderId);
    p.put("uid", actor.id());
    p.put("mod", mod ? 1 : 0);
    List<Row> rows =
        query(
            """
            m.subject_id = :s AND m.folder_id IS :f
            AND ((m.status = 'published' AND m.hidden = 0) OR m.author_id = :uid OR :mod = 1)
            AND m.status != 'rejected'
            ORDER BY m.status = 'pending' DESC, m.created_at DESC
            """,
            p);
    List<Folder> folders =
        db.sql(
                """
                SELECT f.id, f.parent_id, f.name,
                  (SELECT count(*) FROM materials m WHERE m.folder_id = f.id
                     AND m.status = 'published' AND m.hidden = 0) AS cnt
                FROM folders f WHERE f.subject_id = ? AND f.parent_id IS ?
                ORDER BY f.name COLLATE NOCASE
                """)
            .params(subjectId, folderId)
            .query(
                (rs, i) ->
                    new Folder(
                        rs.getLong(1),
                        Rows.longOrNull(rs, "parent_id"),
                        rs.getString(3),
                        rs.getInt(4)))
            .list();
    return new Listing(
        path(subjectId, folderId),
        folders,
        views(actor, rows),
        access.can(actor, Permission.UPLOAD_MATERIALS, groups),
        access.can(actor, Permission.SUGGEST_MATERIALS, groups));
  }

  /** Недавние материалы по видимым предметам (раздел «Материалы»). */
  public List<Item> recent(Actor actor, Long group, int limit) {
    List<Long> scope = access.scope(actor, group);
    if (scope.isEmpty()) {
      return List.of();
    }
    List<Row> rows =
        query(
            """
            m.status = 'published' AND m.hidden = 0 AND EXISTS (
              SELECT 1 FROM subject_groups sg WHERE sg.subject_id = m.subject_id AND sg.group_id IN (:g))
            ORDER BY m.created_at DESC LIMIT :limit
            """,
            Map.of("g", scope, "limit", limit));
    return views(actor, rows);
  }

  /** Очередь премодерации: предложенные материалы в предметах, где пользователь модерирует. */
  public List<Item> pending(Actor actor) {
    Set<Long> mod =
        access.groupsWith(actor, Permission.MODERATE_CONTENT, access.visibleGroups(actor));
    if (mod.isEmpty()) {
      return List.of();
    }
    List<Row> rows =
        query(
            """
            m.status = 'pending' AND EXISTS (
              SELECT 1 FROM subject_groups sg WHERE sg.subject_id = m.subject_id AND sg.group_id IN (:g))
            ORDER BY m.created_at
            """,
            Map.of("g", List.copyOf(mod)));
    return views(actor, rows);
  }

  public Item get(Actor actor, long id) {
    Row r = row(id);
    List<Long> groups = subjectStore.groupIds(r.subjectId());
    access.requireSee(actor, groups);
    if (!visibleTo(actor, r, groups)) {
      throw ApiException.notFound();
    }
    return views(actor, List.of(r)).getFirst();
  }

  private boolean visibleTo(Actor actor, Row r, List<Long> groups) {
    if (isAuthor(actor, r)) {
      return true;
    }
    if (access.can(actor, Permission.MODERATE_CONTENT, groups)) {
      return true;
    }
    return "published".equals(r.status()) && !r.hidden();
  }

  private Row row(long id) {
    List<Row> rows = query("m.id = :id", Map.of("id", id));
    if (rows.isEmpty()) {
      throw ApiException.notFound();
    }
    return rows.getFirst();
  }

  // --- изменение ---

  @Transactional
  public Item create(Actor actor, long subjectId, Input in) {
    subjects.visible(actor, subjectId);
    List<Long> groups = subjectStore.groupIds(subjectId);
    String status;
    if (access.can(actor, Permission.UPLOAD_MATERIALS, groups)) {
      status = "published";
    } else if (access.can(actor, Permission.SUGGEST_MATERIALS, groups)) {
      status = "pending";
    } else {
      throw ApiException.forbidden();
    }
    if (in.folderId() != null) {
      requireFolder(subjectId, in.folderId());
    }
    String kind = in.kind() == null ? "" : in.kind().toLowerCase(Locale.ROOT);
    String title = in.title() == null ? "" : in.title().strip();
    String url = null;
    Long fileId = null;
    switch (kind) {
      case "link" -> {
        url = link(in.url());
        if (title.isEmpty()) {
          title = URI.create(url).getHost();
        }
      }
      case "file" -> {
        if (in.fileId() == null) {
          throw ApiException.invalid("fileId", "Сначала загрузите файл");
        }
        StoredFile f = files.find(in.fileId()).orElseThrow(ApiException::notFound);
        if (f.uploadedBy() == null || f.uploadedBy() != actor.id() || attached(f.id())) {
          throw ApiException.forbidden("Этот файл нельзя прикрепить");
        }
        fileId = f.id();
        if (title.isEmpty()) {
          title = f.name();
        }
      }
      default -> throw ApiException.invalid("kind", "Тип материала: файл или ссылка");
    }
    if (title.length() > 200) {
      throw ApiException.invalid("title", "Название — до 200 символов");
    }
    String description = in.description() == null ? "" : in.description().strip();
    if (description.length() > 2000) {
      throw ApiException.invalid("description", "Описание — до 2000 символов");
    }
    long now = clock.millis();
    long id =
        db.sql(
                """
                INSERT INTO materials (subject_id, folder_id, kind, title, description, url, file_id,
                                       author_id, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """)
            .params(
                subjectId,
                in.folderId(),
                kind,
                title,
                description,
                url,
                fileId,
                actor.id(),
                status,
                now,
                now)
            .query(Long.class)
            .single();
    audit.log(
        actor,
        groups.getFirst(),
        "material." + (status.equals("pending") ? "suggest" : "create"),
        "material",
        id);
    events.publishEvent(new Submitted(id, subjectId, title, status, actor.id()));
    return get(actor, id);
  }

  @Transactional
  public Item update(Actor actor, long id, Input in) {
    Row r = row(id);
    List<Long> groups = subjectStore.groupIds(r.subjectId());
    access.requireSee(actor, groups);
    if (!canEdit(actor, r, groups)) {
      throw ApiException.forbidden();
    }
    String title = in.title() == null ? r.title() : in.title().strip();
    if (title.isEmpty() || title.length() > 200) {
      throw ApiException.invalid("title", "Название: от 1 до 200 символов");
    }
    String description = in.description() == null ? r.description() : in.description().strip();
    Long folder =
        in.folderId() == null ? r.folderId() : (in.folderId() == 0 ? null : in.folderId());
    if (folder != null) {
      requireFolder(r.subjectId(), folder);
    }
    db.sql(
            "UPDATE materials SET title = ?, description = ?, folder_id = ?, updated_at = ?"
                + " WHERE id = ?")
        .params(title, description, folder, clock.millis(), id)
        .update();
    return get(actor, id);
  }

  @Transactional
  public void delete(Actor actor, long id) {
    Row r = row(id);
    List<Long> groups = subjectStore.groupIds(r.subjectId());
    access.requireSee(actor, groups);
    if (!isAuthor(actor, r) && !access.can(actor, Permission.MODERATE_CONTENT, groups)) {
      throw ApiException.forbidden();
    }
    db.sql("DELETE FROM comments WHERE target_type = 'material' AND target_id = ?")
        .param(id)
        .update();
    db.sql("DELETE FROM materials WHERE id = ?").param(id).update();
    if (r.fileId() != null) {
      files.find(r.fileId()).ifPresent(files::delete);
    }
    audit.log(
        actor, groups.getFirst(), "material.delete", "material", id, Map.of("title", r.title()));
  }

  /** Одобрить или отклонить предложенный материал; hide — скрыть опубликованный. */
  @Transactional
  public void moderate(Actor actor, long id, String action) {
    Row r = row(id);
    List<Long> groups = subjectStore.groupIds(r.subjectId());
    if (!access.can(actor, Permission.MODERATE_CONTENT, groups)) {
      throw ApiException.forbidden();
    }
    switch (action) {
      case "approve" ->
          db.sql("UPDATE materials SET status = 'published' WHERE id = ?").param(id).update();
      case "reject" -> {
        db.sql("UPDATE materials SET status = 'rejected' WHERE id = ?").param(id).update();
        if (r.fileId() != null) {
          db.sql("UPDATE materials SET file_id = NULL WHERE id = ?").param(id).update();
          files.find(r.fileId()).ifPresent(files::delete);
        }
      }
      case "hide" -> db.sql("UPDATE materials SET hidden = 1 WHERE id = ?").param(id).update();
      case "unhide" -> db.sql("UPDATE materials SET hidden = 0 WHERE id = ?").param(id).update();
      default -> throw ApiException.badRequest("Неизвестное действие");
    }
    audit.log(actor, groups.getFirst(), "material." + action, "material", id);
    if (action.equals("approve")) {
      events.publishEvent(new Submitted(id, r.subjectId(), r.title(), "published", actor.id()));
    }
  }

  // --- папки ---

  @Transactional
  public Folder createFolder(Actor actor, long subjectId, FolderInput in) {
    subjects.visible(actor, subjectId);
    if (!access.can(actor, Permission.UPLOAD_MATERIALS, subjectStore.groupIds(subjectId))) {
      throw ApiException.forbidden();
    }
    if (in.parentId() != null) {
      requireFolder(subjectId, in.parentId());
    }
    String name = folderName(in.name());
    long id =
        db.sql(
                "INSERT INTO folders (subject_id, parent_id, name, created_by, created_at)"
                    + " VALUES (?, ?, ?, ?, ?) RETURNING id")
            .params(subjectId, in.parentId(), name, actor.id(), clock.millis())
            .query(Long.class)
            .single();
    return new Folder(id, in.parentId(), name, 0);
  }

  @Transactional
  public void renameFolder(Actor actor, long folderId, String name) {
    long subjectId = folderSubject(folderId);
    subjects.visible(actor, subjectId);
    if (!access.can(actor, Permission.UPLOAD_MATERIALS, subjectStore.groupIds(subjectId))) {
      throw ApiException.forbidden();
    }
    db.sql("UPDATE folders SET name = ? WHERE id = ?").params(folderName(name), folderId).update();
  }

  /** Удаление папки вместе с вложенными папками и материалами. */
  @Transactional
  public void deleteFolder(Actor actor, long folderId) {
    long subjectId = folderSubject(folderId);
    subjects.visible(actor, subjectId);
    List<Long> groups = subjectStore.groupIds(subjectId);
    if (!access.can(actor, Permission.MODERATE_CONTENT, groups)
        && !access.can(actor, Permission.UPLOAD_MATERIALS, groups)) {
      throw ApiException.forbidden();
    }
    List<Long> fileIds =
        db.sql(
                """
                WITH RECURSIVE tree(id) AS (
                  SELECT ? UNION ALL SELECT f.id FROM folders f JOIN tree t ON f.parent_id = t.id)
                SELECT m.file_id FROM materials m WHERE m.folder_id IN (SELECT id FROM tree)
                  AND m.file_id IS NOT NULL
                """)
            .param(folderId)
            .query(Long.class)
            .list();
    db.sql("DELETE FROM folders WHERE id = ?").param(folderId).update();
    for (Long f : fileIds) {
      files.find(f).ifPresent(files::delete);
    }
    audit.log(actor, groups.getFirst(), "folder.delete", "folder", folderId);
  }

  private long folderSubject(long folderId) {
    return db.sql("SELECT subject_id FROM folders WHERE id = ?")
        .param(folderId)
        .query(Long.class)
        .optional()
        .orElseThrow(ApiException::notFound);
  }

  private void requireFolder(long subjectId, long folderId) {
    if (folderSubject(folderId) != subjectId) {
      throw ApiException.notFound();
    }
  }

  private List<Crumb> path(long subjectId, Long folderId) {
    List<Crumb> out = new ArrayList<>();
    Long cur = folderId;
    int guard = 0;
    while (cur != null && guard++ < 32) {
      record F(Long parent, String name) {}
      F f =
          db.sql("SELECT parent_id, name FROM folders WHERE id = ?")
              .param(cur)
              .query((rs, i) -> new F(Rows.longOrNull(rs, "parent_id"), rs.getString(2)))
              .single();
      out.addFirst(new Crumb(cur, f.name()));
      cur = f.parent();
    }
    return out;
  }

  // --- доступ к файлам ---

  /** Может ли пользователь скачать файл: он автор загрузки или файл прикреплён к видимому. */
  public boolean canRead(Actor actor, StoredFile f) {
    if (f.uploadedBy() != null && f.uploadedBy() == actor.id()) {
      return true;
    }
    for (Row r : query("m.file_id = :f", Map.of("f", f.id()))) {
      List<Long> groups = subjectStore.groupIds(r.subjectId());
      if (access.canSee(actor, groups) && visibleTo(actor, r, groups)) {
        return true;
      }
    }
    List<Long> homework =
        db.sql("SELECT homework_id FROM homework_attachments WHERE file_id = ?")
            .param(f.id())
            .query(Long.class)
            .list();
    for (Long h : homework) {
      boolean hidden =
          db.sql("SELECT hidden FROM homework WHERE id = ?").param(h).query(Integer.class).single()
              != 0;
      List<Long> groups =
          db.sql("SELECT group_id FROM homework_targets WHERE homework_id = ?")
              .param(h)
              .query(Long.class)
              .list();
      if (access.canSee(actor, groups)
          && (!hidden || access.can(actor, Permission.MODERATE_CONTENT, groups))) {
        return true;
      }
    }
    return false;
  }

  /** Может ли пользователь вообще загружать файлы (хоть куда-то). */
  public boolean canUploadAnywhere(Actor actor) {
    List<Long> groups = access.visibleGroups(actor);
    return access.can(actor, Permission.UPLOAD_MATERIALS, groups)
        || access.can(actor, Permission.SUGGEST_MATERIALS, groups)
        || access.can(actor, Permission.PUBLISH_HOMEWORK, groups);
  }

  private boolean attached(long fileId) {
    return db.sql(
                "SELECT (SELECT count(*) FROM materials WHERE file_id = ?)"
                    + " + (SELECT count(*) FROM homework_attachments WHERE file_id = ?)")
            .params(fileId, fileId)
            .query(Integer.class)
            .single()
        > 0;
  }

  private boolean isAuthor(Actor actor, Row r) {
    return r.authorId() != null && r.authorId() == actor.id();
  }

  private boolean canEdit(Actor actor, Row r, List<Long> groups) {
    return isAuthor(actor, r) || access.can(actor, Permission.MODERATE_CONTENT, groups);
  }

  private List<Item> views(Actor actor, List<Row> rows) {
    Set<Long> authorIds = new HashSet<>();
    rows.forEach(
        r -> {
          if (r.authorId() != null) {
            authorIds.add(r.authorId());
          }
        });
    Map<Long, Person> authors = people.load(authorIds);
    Map<Long, List<Long>> groupsCache = new HashMap<>();
    List<Item> out = new ArrayList<>();
    for (Row r : rows) {
      List<Long> groups = groupsCache.computeIfAbsent(r.subjectId(), subjectStore::groupIds);
      boolean mod = access.can(actor, Permission.MODERATE_CONTENT, groups);
      out.add(
          new Item(
              r.id(),
              r.subjectId(),
              r.subjectName(),
              r.subjectColor(),
              r.folderId(),
              r.kind(),
              r.title(),
              r.description(),
              r.url(),
              r.fileId() == null
                  ? null
                  : new FileInfo(r.fileId(), r.fileName(), r.fileMime(), r.fileSize()),
              people.get(authors, r.authorId()),
              r.status(),
              r.hidden(),
              r.createdAt(),
              r.comments(),
              new Can(isAuthor(actor, r) || mod, isAuthor(actor, r) || mod, mod)));
    }
    return out;
  }

  private static String folderName(String raw) {
    String n = raw == null ? "" : raw.strip();
    if (n.isEmpty() || n.length() > 80 || n.contains("/")) {
      throw ApiException.invalid("name", "Название папки: от 1 до 80 символов, без «/»");
    }
    return n;
  }

  private static String link(String raw) {
    String u = raw == null ? "" : raw.strip();
    try {
      URI uri = URI.create(u);
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      if ((!scheme.equals("https") && !scheme.equals("http")) || uri.getHost() == null) {
        throw new IllegalArgumentException();
      }
      if (u.length() > 2000) {
        throw new IllegalArgumentException();
      }
      return u;
    } catch (IllegalArgumentException e) {
      throw ApiException.invalid("url", "Ссылка должна начинаться с http:// или https://");
    }
  }
}
