package app.groupbase.content;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Целевые группы новостей и ДЗ: таблицы post_targets и homework_targets. */
@Repository
public class Targets {

  public enum Kind {
    POST("post_targets", "post_id"),
    HOMEWORK("homework_targets", "homework_id");

    final String table;
    final String column;

    Kind(String table, String column) {
      this.table = table;
      this.column = column;
    }
  }

  private final JdbcClient db;

  public Targets(JdbcClient db) {
    this.db = db;
  }

  public List<Long> of(Kind k, long id) {
    return db.sql("SELECT group_id FROM " + k.table + " WHERE " + k.column + " = ?")
        .param(id)
        .query(Long.class)
        .list();
  }

  public Map<Long, List<Long>> of(Kind k, Collection<Long> ids) {
    Map<Long, List<Long>> out = new HashMap<>();
    if (ids.isEmpty()) {
      return out;
    }
    db.sql(
            "SELECT "
                + k.column
                + ", group_id FROM "
                + k.table
                + " WHERE "
                + k.column
                + " IN (:ids)")
        .param("ids", ids)
        .query(
            rs -> {
              out.computeIfAbsent(rs.getLong(1), x -> new ArrayList<>()).add(rs.getLong(2));
            });
    return out;
  }

  public void set(Kind k, long id, Collection<Long> groups) {
    db.sql("DELETE FROM " + k.table + " WHERE " + k.column + " = ?").param(id).update();
    for (Long g : groups) {
      db.sql("INSERT INTO " + k.table + " (" + k.column + ", group_id) VALUES (?, ?)")
          .params(id, g)
          .update();
    }
  }

  /** Непустой список для IN (...): пустой заменяется на заведомо несуществующий id. */
  static List<Long> nonEmpty(Collection<Long> ids) {
    return ids.isEmpty() ? List.of(-1L) : List.copyOf(ids);
  }
}
