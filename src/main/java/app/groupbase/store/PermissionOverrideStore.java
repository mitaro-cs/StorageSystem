package app.groupbase.store;

import app.groupbase.auth.GroupRole;
import app.groupbase.auth.Permission;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PermissionOverrideStore {

  /** groupId == null — значение для всего инстанса. */
  public record Row(Long groupId, GroupRole role, Permission permission, boolean allowed) {}

  private final JdbcClient db;

  public PermissionOverrideStore(JdbcClient db) {
    this.db = db;
  }

  public List<Row> all() {
    return db.sql("SELECT group_id, role, permission, allowed FROM group_permission_overrides")
        .query(
            (rs, i) ->
                new Row(
                    Rows.longOrNull(rs, "group_id"),
                    GroupRole.of(rs.getString("role")),
                    Permission.of(rs.getString("permission")),
                    Rows.bool(rs, "allowed")))
        .list();
  }

  public void upsert(
      Long groupId, GroupRole role, Permission permission, boolean allowed, long by, long now) {
    db.sql(
            """
            INSERT INTO group_permission_overrides
              (group_id, role, permission, allowed, updated_by, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (ifnull(group_id, 0), role, permission)
            DO UPDATE SET allowed = excluded.allowed, updated_by = excluded.updated_by,
                          updated_at = excluded.updated_at
            """)
        .params(groupId, role.id(), permission.id(), allowed ? 1 : 0, by, now)
        .update();
  }

  /** Сброс к значению уровнем выше (инстанс или матрица по умолчанию). */
  public void delete(Long groupId, GroupRole role, Permission permission) {
    db.sql(
            "DELETE FROM group_permission_overrides WHERE ifnull(group_id, 0) = ifnull(?, 0)"
                + " AND role = ? AND permission = ?")
        .params(groupId, role.id(), permission.id())
        .update();
  }
}
