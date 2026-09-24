package app.groupbase.content;

import app.groupbase.store.Rows;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SubjectStore {

  public record Row(
      long id,
      String name,
      String teacher,
      String color,
      String avatar,
      Long createdBy,
      long createdAt,
      Long archivedAt,
      String chatUrl,
      String icon) {}

  public record GroupRef(long id, String name) {}

  public record LinkRequest(
      long id,
      long subjectId,
      String subjectName,
      long fromGroupId,
      String fromGroupName,
      long toGroupId,
      String toGroupName,
      Long requestedBy,
      String status,
      long createdAt) {}

  static final RowMapper<Row> MAPPER =
      (rs, i) ->
          new Row(
              rs.getLong("id"),
              rs.getString("name"),
              rs.getString("teacher"),
              rs.getString("color"),
              rs.getString("avatar"),
              Rows.longOrNull(rs, "created_by"),
              rs.getLong("created_at"),
              Rows.longOrNull(rs, "archived_at"),
              rs.getString("chat_url"),
              rs.getString("icon"));

  private final JdbcClient db;

  public SubjectStore(JdbcClient db) {
    this.db = db;
  }

  public Optional<Row> find(long id) {
    return db.sql("SELECT * FROM subjects WHERE id = ?").param(id).query(MAPPER).optional();
  }

  public List<Row> visible(Collection<Long> groupIds) {
    if (groupIds.isEmpty()) {
      return List.of();
    }
    return db.sql(
            """
            SELECT * FROM subjects s WHERE EXISTS (
              SELECT 1 FROM subject_groups sg WHERE sg.subject_id = s.id AND sg.group_id IN (:g))
            ORDER BY s.archived_at IS NOT NULL, s.name COLLATE NOCASE
            """)
        .param("g", groupIds)
        .query(MAPPER)
        .list();
  }

  public long insert(String name, String teacher, String color, long createdBy, long now) {
    return db.sql(
            "INSERT INTO subjects (name, teacher, color, created_by, created_at)"
                + " VALUES (?, ?, ?, ?, ?) RETURNING id")
        .params(name, teacher, color, createdBy, now)
        .query(Long.class)
        .single();
  }

  public void update(long id, String name, String teacher, String color) {
    db.sql("UPDATE subjects SET name = ?, teacher = ?, color = ? WHERE id = ?")
        .params(name, teacher, color, id)
        .update();
  }

  /** Иконка из встроенного набора (null — подбирается по названию). */
  public void setIcon(long id, String icon) {
    db.sql("UPDATE subjects SET icon = ? WHERE id = ?").params(icon, id).update();
  }

  /** Чат предмета в Telegram (null — нет). */
  public void setChat(long id, String url) {
    db.sql("UPDATE subjects SET chat_url = ? WHERE id = ?").params(url, id).update();
  }

  public void setArchived(long id, Long at) {
    db.sql("UPDATE subjects SET archived_at = ? WHERE id = ?").params(at, id).update();
  }

  public void setAvatar(long id, String avatar) {
    db.sql("UPDATE subjects SET avatar = ? WHERE id = ?").params(avatar, id).update();
  }

  public void delete(long id) {
    db.sql("DELETE FROM subjects WHERE id = ?").param(id).update();
  }

  // --- связи с группами ---

  public List<Long> groupIds(long subjectId) {
    return db.sql("SELECT group_id FROM subject_groups WHERE subject_id = ? ORDER BY linked_at")
        .param(subjectId)
        .query(Long.class)
        .list();
  }

  public Map<Long, List<GroupRef>> groupsOf(Collection<Long> subjectIds) {
    Map<Long, List<GroupRef>> out = new HashMap<>();
    if (subjectIds.isEmpty()) {
      return out;
    }
    db.sql(
            """
            SELECT sg.subject_id, g.id, g.name FROM subject_groups sg
            JOIN study_groups g ON g.id = sg.group_id
            WHERE sg.subject_id IN (:ids) ORDER BY sg.linked_at
            """)
        .param("ids", subjectIds)
        .query(
            rs -> {
              out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                  .add(new GroupRef(rs.getLong(2), rs.getString(3)));
            });
    return out;
  }

  public void link(long subjectId, long groupId, long now) {
    db.sql(
            "INSERT INTO subject_groups (subject_id, group_id, linked_at) VALUES (?, ?, ?)"
                + " ON CONFLICT DO NOTHING")
        .params(subjectId, groupId, now)
        .update();
  }

  public void unlink(long subjectId, long groupId) {
    db.sql("DELETE FROM subject_groups WHERE subject_id = ? AND group_id = ?")
        .params(subjectId, groupId)
        .update();
  }

  // --- закрепление ---

  public Set<Long> pinned(long userId) {
    return new HashSet<>(
        db.sql("SELECT subject_id FROM subject_pins WHERE user_id = ?")
            .param(userId)
            .query(Long.class)
            .list());
  }

  public void pin(long userId, long subjectId, long now) {
    db.sql(
            "INSERT INTO subject_pins (user_id, subject_id, pinned_at) VALUES (?, ?, ?)"
                + " ON CONFLICT DO NOTHING")
        .params(userId, subjectId, now)
        .update();
  }

  public void unpin(long userId, long subjectId) {
    db.sql("DELETE FROM subject_pins WHERE user_id = ? AND subject_id = ?")
        .params(userId, subjectId)
        .update();
  }

  // --- запросы на связывание ---

  private static final String REQUEST_SELECT =
      """
      SELECT r.*, s.name AS subject_name, fg.name AS from_name, tg.name AS to_name
      FROM subject_link_requests r
      JOIN subjects s ON s.id = r.subject_id
      JOIN study_groups fg ON fg.id = r.from_group_id
      JOIN study_groups tg ON tg.id = r.to_group_id
      """;

  private static final RowMapper<LinkRequest> REQUEST =
      (rs, i) ->
          new LinkRequest(
              rs.getLong("id"),
              rs.getLong("subject_id"),
              rs.getString("subject_name"),
              rs.getLong("from_group_id"),
              rs.getString("from_name"),
              rs.getLong("to_group_id"),
              rs.getString("to_name"),
              Rows.longOrNull(rs, "requested_by"),
              rs.getString("status"),
              rs.getLong("created_at"));

  public long insertRequest(long subjectId, long from, long to, long by, long now) {
    return db.sql(
            "INSERT INTO subject_link_requests (subject_id, from_group_id, to_group_id,"
                + " requested_by, created_at) VALUES (?, ?, ?, ?, ?) RETURNING id")
        .params(subjectId, from, to, by, now)
        .query(Long.class)
        .single();
  }

  public Optional<LinkRequest> request(long id) {
    return db.sql(REQUEST_SELECT + " WHERE r.id = ?").param(id).query(REQUEST).optional();
  }

  public boolean pendingExists(long subjectId, long toGroup) {
    return db.sql(
                "SELECT count(*) FROM subject_link_requests WHERE subject_id = ? AND to_group_id = ?"
                    + " AND status = 'pending'")
            .params(subjectId, toGroup)
            .query(Integer.class)
            .single()
        > 0;
  }

  /** Ожидающие запросы, где группа-получатель или группа-отправитель входит в список. */
  public List<LinkRequest> pendingFor(Collection<Long> groupIds) {
    if (groupIds.isEmpty()) {
      return List.of();
    }
    return db.sql(
            REQUEST_SELECT
                + " WHERE r.status = 'pending' AND (r.to_group_id IN (:g) OR r.from_group_id IN (:g))"
                + " ORDER BY r.created_at DESC")
        .param("g", groupIds)
        .query(REQUEST)
        .list();
  }

  public boolean decide(long id, String status, long by, long now) {
    return db.sql(
                "UPDATE subject_link_requests SET status = ?, decided_by = ?, decided_at = ?"
                    + " WHERE id = ? AND status = 'pending'")
            .params(status, by, now, id)
            .update()
        == 1;
  }
}
