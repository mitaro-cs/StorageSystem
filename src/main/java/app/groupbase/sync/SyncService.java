package app.groupbase.sync;

import app.groupbase.accounts.GroupService;
import app.groupbase.auth.Actor;
import app.groupbase.content.Access;
import app.groupbase.content.CommentService;
import app.groupbase.content.CommentService.Parent;
import app.groupbase.content.HomeworkService;
import app.groupbase.content.MaterialService;
import app.groupbase.content.NewsService;
import app.groupbase.content.SubjectService;
import app.groupbase.store.Member;
import app.groupbase.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Офлайн-синхронизация. Клиент хранит курсор — номер последнего увиденного изменения в журнале
 * {@code changes}. Сервер отдаёт изменившиеся объекты уже с учётом прав: что видно — обновить, что
 * пропало или стало невидимым — удалить. Предметы и участники маленькие и приходят целиком. Если
 * курсор слишком старый, изменений слишком много или у пользователя поменялись группы и роли —
 * полный снимок.
 */
@Service
public class SyncService {

  /** Больше изменений разом — дешевле отдать полный снимок. */
  static final int MAX_CHANGES = 5000;

  /** Полный снимок: новости и задания за последний год (и все будущие задания). */
  static final Duration WINDOW = Duration.ofDays(365);

  /** Журнал хранится месяц; кто не синхронизировался дольше — получит полный снимок. */
  public static final Duration RETENTION = Duration.ofDays(30);

  private static final int CHUNK = 500;

  public record Delta<T>(List<T> upsert, List<Long> delete) {}

  /** Все комментарии к одному объекту — заменяют то, что было на устройстве. */
  public record Comments(String type, long id, List<CommentService.Comment> items) {}

  public record Result(
      long cursor,
      boolean full,
      long serverTime,
      List<SubjectService.SubjectView> subjects,
      Map<Long, List<Member>> members,
      Delta<NewsService.Item> news,
      Delta<HomeworkService.Item> homework,
      Delta<MaterialService.Item> materials,
      Delta<MaterialService.FolderRef> folders,
      List<Comments> comments) {}

  private record Change(String kind, long ref) {}

  private final JdbcClient db;
  private final Access access;
  private final NewsService news;
  private final HomeworkService homework;
  private final MaterialService materials;
  private final SubjectService subjects;
  private final CommentService comments;
  private final GroupService groups;
  private final Clock clock;

  public SyncService(
      JdbcClient db,
      Access access,
      NewsService news,
      HomeworkService homework,
      MaterialService materials,
      SubjectService subjects,
      CommentService comments,
      GroupService groups,
      Clock clock) {
    this.db = db;
    this.access = access;
    this.news = news;
    this.homework = homework;
    this.materials = materials;
    this.subjects = subjects;
    this.comments = comments;
    this.groups = groups;
    this.clock = clock;
  }

  /** Последний номер в журнале; AUTOINCREMENT не переиспользует номера даже после уборки. */
  long cursor() {
    return db.sql("SELECT seq FROM sqlite_sequence WHERE name = 'changes'")
        .query(Long.class)
        .optional()
        .orElse(0L);
  }

  public Result sync(Actor actor, long after) {
    long cursor = cursor();
    Long oldest = db.sql("SELECT min(seq) FROM changes").query(Long.class).optional().orElse(null);
    boolean full = after <= 0 || after > cursor || (oldest != null && after + 1 < oldest);
    List<Change> changes = List.of();
    if (!full) {
      changes =
          db.sql("SELECT kind, ref_id FROM changes WHERE seq > ? AND seq <= ? ORDER BY seq LIMIT ?")
              .params(after, cursor, MAX_CHANGES + 1)
              .query((rs, i) -> new Change(rs.getString(1), rs.getLong(2)))
              .list();
      full =
          changes.size() > MAX_CHANGES
              || changes.stream().anyMatch(c -> c.kind().equals("reset") && c.ref() == actor.id());
    }

    List<SubjectService.SubjectView> subjectList = subjects.list(actor, null);
    Map<Long, List<Member>> memberMap = new LinkedHashMap<>();
    for (Long g : access.visibleGroups(actor)) {
      memberMap.put(g, groups.members(actor, g));
    }

    if (full) {
      long since = clock.millis() - WINDOW.toMillis();
      var n = news.visible(actor, null, since);
      var h = homework.visible(actor, null, since);
      var m = materials.visible(actor, null);
      List<Comments> c = new ArrayList<>();
      n.stream()
          .filter(x -> x.comments() > 0)
          .forEach(x -> c.add(list(actor, Parent.POST, x.id())));
      h.stream()
          .filter(x -> x.comments() > 0)
          .forEach(x -> c.add(list(actor, Parent.HOMEWORK, x.id())));
      m.stream()
          .filter(x -> x.comments() > 0)
          .forEach(x -> c.add(list(actor, Parent.MATERIAL, x.id())));
      return new Result(
          cursor,
          true,
          clock.millis(),
          subjectList,
          memberMap,
          new Delta<>(n, List.of()),
          new Delta<>(h, List.of()),
          new Delta<>(m, List.of()),
          new Delta<>(materials.visibleFolders(actor, null), List.of()),
          c);
    }

    Set<Long> postIds = new LinkedHashSet<>();
    Set<Long> hwIds = new LinkedHashSet<>();
    Set<Long> matIds = new LinkedHashSet<>();
    Set<Long> folderIds = new LinkedHashSet<>();
    Map<Parent, Set<Long>> commentParents = new LinkedHashMap<>();
    for (Change ch : changes) {
      switch (ch.kind()) {
        case "post" -> postIds.add(ch.ref());
        case "homework" -> hwIds.add(ch.ref());
        case "material" -> matIds.add(ch.ref());
        case "folder" -> folderIds.add(ch.ref());
        case "comments:post" ->
            commentParents.computeIfAbsent(Parent.POST, k -> new LinkedHashSet<>()).add(ch.ref());
        case "comments:homework" ->
            commentParents
                .computeIfAbsent(Parent.HOMEWORK, k -> new LinkedHashSet<>())
                .add(ch.ref());
        case "comments:material" ->
            commentParents
                .computeIfAbsent(Parent.MATERIAL, k -> new LinkedHashSet<>())
                .add(ch.ref());
        default -> {
          // «reset» чужого пользователя нас не касается
        }
      }
    }
    List<Long> scope = access.visibleGroups(actor);
    var n =
        hideForeign(
            delta(postIds, ids -> news.visible(actor, ids, 0), NewsService.Item::id),
            FOREIGN_POSTS,
            scope);
    var h =
        hideForeign(
            delta(hwIds, ids -> homework.visible(actor, ids, 0), HomeworkService.Item::id),
            FOREIGN_HOMEWORK,
            scope);
    var m =
        hideForeign(
            delta(matIds, ids -> materials.visible(actor, ids), MaterialService.Item::id),
            FOREIGN_MATERIALS,
            scope);
    var f =
        hideForeign(
            delta(
                folderIds,
                ids -> materials.visibleFolders(actor, ids),
                MaterialService.FolderRef::id),
            FOREIGN_FOLDERS,
            scope);
    // Комментарии — только для объектов, которые пользователь видит; невидимые уже в delete.
    Map<Parent, Set<Long>> visibleParents =
        Map.of(
            Parent.POST, ids(n.upsert(), NewsService.Item::id),
            Parent.HOMEWORK, ids(h.upsert(), HomeworkService.Item::id),
            Parent.MATERIAL, ids(m.upsert(), MaterialService.Item::id));
    List<Comments> c = new ArrayList<>();
    commentParents.forEach(
        (parent, idSet) ->
            idSet.stream()
                .filter(visibleParents.get(parent)::contains)
                .forEach(id -> c.add(list(actor, parent, id))));
    return new Result(cursor, false, clock.millis(), subjectList, memberMap, n, h, m, f, c);
  }

  private Comments list(Actor actor, Parent parent, long id) {
    try {
      return new Comments(parent.id(), id, comments.list(actor, parent, id));
    } catch (ApiException e) {
      return new Comments(parent.id(), id, List.of());
    }
  }

  private static <T> Set<Long> ids(List<T> items, ToLongFunction<T> id) {
    Set<Long> out = new HashSet<>();
    items.forEach(x -> out.add(id.applyAsLong(x)));
    return out;
  }

  /** Видимые из изменившихся — обновить, остальные — удалить. Запросы порциями по 500 id. */
  private static <T> Delta<T> delta(
      Collection<Long> changed, Function<List<Long>, List<T>> load, ToLongFunction<T> id) {
    if (changed.isEmpty()) {
      return new Delta<>(List.of(), List.of());
    }
    List<Long> all = List.copyOf(changed);
    List<T> up = new ArrayList<>();
    for (int i = 0; i < all.size(); i += CHUNK) {
      up.addAll(load.apply(all.subList(i, Math.min(all.size(), i + CHUNK))));
    }
    Set<Long> seen = ids(up, id);
    return new Delta<>(up, all.stream().filter(x -> !seen.contains(x)).toList());
  }

  // Объекты, которые существуют, но относятся только к чужим группам: их id в «удалить» не отдаём,
  // иначе по ним можно судить об активности в чужих группах.
  private static final String FOREIGN_POSTS =
      "SELECT p.id FROM posts p WHERE p.id IN (:ids) AND NOT EXISTS ("
          + "SELECT 1 FROM post_targets t WHERE t.post_id = p.id AND t.group_id IN (:g))";
  private static final String FOREIGN_HOMEWORK =
      "SELECT h.id FROM homework h WHERE h.id IN (:ids) AND NOT EXISTS ("
          + "SELECT 1 FROM homework_targets t WHERE t.homework_id = h.id AND t.group_id IN (:g))";
  private static final String FOREIGN_MATERIALS =
      "SELECT m.id FROM materials m WHERE m.id IN (:ids) AND NOT EXISTS ("
          + "SELECT 1 FROM subject_groups sg WHERE sg.subject_id = m.subject_id"
          + " AND sg.group_id IN (:g))";
  private static final String FOREIGN_FOLDERS =
      "SELECT f.id FROM folders f WHERE f.id IN (:ids) AND NOT EXISTS ("
          + "SELECT 1 FROM subject_groups sg WHERE sg.subject_id = f.subject_id"
          + " AND sg.group_id IN (:g))";

  private <T> Delta<T> hideForeign(Delta<T> d, String foreignSql, List<Long> scope) {
    if (d.delete().isEmpty()) {
      return d;
    }
    Set<Long> foreign = new HashSet<>();
    List<Long> all = d.delete();
    for (int i = 0; i < all.size(); i += CHUNK) {
      foreign.addAll(
          db.sql(foreignSql)
              .param("ids", all.subList(i, Math.min(all.size(), i + CHUNK)))
              .param("g", scope.isEmpty() ? List.of(-1L) : scope)
              .query(Long.class)
              .list());
    }
    return new Delta<>(d.upsert(), all.stream().filter(x -> !foreign.contains(x)).toList());
  }

  /** Уборка журнала: изменения старше срока хранения (по часам SQLite, как и отметки в журнале). */
  public int purge() {
    return db.sql(
            "DELETE FROM changes"
                + " WHERE created_at < CAST(unixepoch('subsec') * 1000 AS INTEGER) - ?")
        .param(RETENTION.toMillis())
        .update();
  }
}
