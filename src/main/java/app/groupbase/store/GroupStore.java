package app.groupbase.store;

import app.groupbase.auth.GroupRole;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class GroupStore {

  static final RowMapper<Group> MAPPER =
      (rs, i) ->
          new Group(
              rs.getLong("id"),
              rs.getString("slug"),
              rs.getString("name"),
              rs.getString("university"),
              Rows.intOrNull(rs, "course"),
              rs.getString("avatar"),
              rs.getLong("created_at"),
              Rows.longOrNull(rs, "archived_at"));

  static final RowMapper<Member> MEMBER =
      (rs, i) ->
          new Member(
              rs.getLong("user_id"),
              rs.getString("username"),
              rs.getString("display_name"),
              rs.getString("avatar"),
              User.Status.of(rs.getString("status")),
              GroupRole.of(rs.getString("role")),
              rs.getLong("joined_at"));

  private final JdbcClient db;

  public GroupStore(JdbcClient db) {
    this.db = db;
  }

  public Optional<Group> find(long id) {
    return db.sql("SELECT * FROM study_groups WHERE id = ?").param(id).query(MAPPER).optional();
  }

  public List<Group> listAll() {
    return db.sql("SELECT * FROM study_groups ORDER BY archived_at IS NOT NULL, name")
        .query(MAPPER)
        .list();
  }

  public List<Group> listByIds(Collection<Long> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return db.sql(
            "SELECT * FROM study_groups WHERE id IN (:ids) ORDER BY archived_at IS NOT NULL, name")
        .param("ids", ids)
        .query(MAPPER)
        .list();
  }

  public long count() {
    return db.sql("SELECT count(*) FROM study_groups").query(Long.class).single();
  }

  public boolean slugTaken(String slug) {
    return db.sql("SELECT count(*) FROM study_groups WHERE slug = ?")
            .param(slug)
            .query(Integer.class)
            .single()
        > 0;
  }

  public long insert(String slug, String name, String university, Integer course, long now) {
    return db.sql(
            "INSERT INTO study_groups (slug, name, university, course, created_at)"
                + " VALUES (?, ?, ?, ?, ?) RETURNING id")
        .params(slug, name, university, course, now)
        .query(Long.class)
        .single();
  }

  public void update(long id, String name, String university, Integer course) {
    db.sql("UPDATE study_groups SET name = ?, university = ?, course = ? WHERE id = ?")
        .params(name, university, course, id)
        .update();
  }

  public void setArchived(long id, Long archivedAt) {
    db.sql("UPDATE study_groups SET archived_at = ? WHERE id = ?").params(archivedAt, id).update();
  }

  public void setAvatar(long id, String avatar) {
    db.sql("UPDATE study_groups SET avatar = ? WHERE id = ?").params(avatar, id).update();
  }

  // --- членство ---

  public Optional<GroupRole> role(long userId, long groupId) {
    return db.sql("SELECT role FROM memberships WHERE user_id = ? AND group_id = ?")
        .params(userId, groupId)
        .query((rs, i) -> GroupRole.of(rs.getString(1)))
        .optional();
  }

  /** Все членства пользователя: группа → роль. */
  public Map<Long, GroupRole> rolesOf(long userId) {
    return db
        .sql("SELECT group_id, role FROM memberships WHERE user_id = ?")
        .param(userId)
        .query((rs, i) -> Map.entry(rs.getLong(1), GroupRole.of(rs.getString(2))))
        .list()
        .stream()
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  /** Для чек-листа «Первые шаги»: что в группе уже есть. */
  public record Progress(int subjects, int members, int homework, int news) {}

  public Progress progress(long groupId) {
    return db.sql(
            """
            SELECT
              (SELECT count(*) FROM subject_groups WHERE group_id = :g) AS subjects,
              (SELECT count(*) FROM memberships WHERE group_id = :g) AS members,
              (SELECT count(*) FROM homework_targets WHERE group_id = :g) AS homework,
              (SELECT count(*) FROM post_targets WHERE group_id = :g) AS news
            """)
        .param("g", groupId)
        .query(
            (rs, i) ->
                new Progress(
                    rs.getInt("subjects"),
                    rs.getInt("members"),
                    rs.getInt("homework"),
                    rs.getInt("news")))
        .single();
  }

  public List<Member> members(long groupId) {
    return db.sql(
            """
            SELECT m.user_id, u.username, u.display_name, u.avatar, u.status, m.role, m.joined_at
            FROM memberships m JOIN users u ON u.id = m.user_id
            WHERE m.group_id = ? AND u.status != 'deleted'
            ORDER BY CASE m.role WHEN 'headman' THEN 0 WHEN 'deputy' THEN 1 ELSE 2 END,
                     u.display_name
            """)
        .param(groupId)
        .query(MEMBER)
        .list();
  }

  public void addMember(long userId, long groupId, GroupRole role, long now) {
    db.sql(
            "INSERT INTO memberships (user_id, group_id, role, joined_at) VALUES (?, ?, ?, ?)"
                + " ON CONFLICT (user_id, group_id) DO NOTHING")
        .params(userId, groupId, role.id(), now)
        .update();
  }

  public void setRole(long userId, long groupId, GroupRole role) {
    db.sql("UPDATE memberships SET role = ? WHERE user_id = ? AND group_id = ?")
        .params(role.id(), userId, groupId)
        .update();
  }

  public void removeMember(long userId, long groupId) {
    db.sql("DELETE FROM memberships WHERE user_id = ? AND group_id = ?")
        .params(userId, groupId)
        .update();
  }

  public void removeAllMemberships(long userId) {
    db.sql("DELETE FROM memberships WHERE user_id = ?").param(userId).update();
  }
}
