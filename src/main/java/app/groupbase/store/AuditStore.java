package app.groupbase.store;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AuditStore {

  public record Entry(
      long id,
      long at,
      Long actorId,
      String actorName,
      Long groupId,
      String action,
      String targetType,
      Long targetId,
      String details,
      String ip) {}

  private final JdbcClient db;

  public AuditStore(JdbcClient db) {
    this.db = db;
  }

  public void insert(
      long at,
      Long actorId,
      Long groupId,
      String action,
      String targetType,
      Long targetId,
      String details,
      String ip) {
    db.sql(
            "INSERT INTO audit_log (at, actor_id, group_id, action, target_type, target_id,"
                + " details, ip) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
        .params(at, actorId, groupId, action, targetType, targetId, details, ip)
        .update();
  }

  /**
   * Записи журнала, новые сначала. groupIds == null — все записи (admin/moderator), иначе только
   * записи этих групп. beforeId — курсор пагинации.
   */
  public List<Entry> list(List<Long> groupIds, Long beforeId, int limit) {
    String where = groupIds == null ? "1 = 1" : "a.group_id IN (:groups)";
    var q =
        db.sql(
                """
                SELECT a.*, u.display_name AS actor_name FROM audit_log a
                LEFT JOIN users u ON u.id = a.actor_id
                WHERE %s AND (:before IS NULL OR a.id < :before)
                ORDER BY a.id DESC LIMIT :limit
                """
                    .formatted(where))
            .param("before", beforeId)
            .param("limit", limit);
    if (groupIds != null) {
      if (groupIds.isEmpty()) {
        return List.of();
      }
      q = q.param("groups", groupIds);
    }
    return q.query(
            (rs, i) ->
                new Entry(
                    rs.getLong("id"),
                    rs.getLong("at"),
                    Rows.longOrNull(rs, "actor_id"),
                    rs.getString("actor_name"),
                    Rows.longOrNull(rs, "group_id"),
                    rs.getString("action"),
                    rs.getString("target_type"),
                    Rows.longOrNull(rs, "target_id"),
                    rs.getString("details"),
                    rs.getString("ip")))
        .list();
  }

  /** Обнуляет IP в записях старше порога (хранение IP — не дольше 30 дней). */
  public int forgetIps(long olderThan) {
    return db.sql("UPDATE audit_log SET ip = NULL WHERE ip IS NOT NULL AND at < ?")
        .param(olderThan)
        .update();
  }
}
