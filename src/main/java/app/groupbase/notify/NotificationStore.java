package app.groupbase.notify;

import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Уведомления в приложении (колокольчик). */
@Repository
public class NotificationStore {

  public record Notification(
      long id, String kind, String title, String body, String url, long createdAt, boolean read) {}

  private final JdbcClient db;

  public NotificationStore(JdbcClient db) {
    this.db = db;
  }

  public void insert(
      Collection<Long> users, String kind, String title, String body, String url, long now) {
    for (Long u : users) {
      db.sql(
              "INSERT INTO notifications (user_id, kind, title, body, url, created_at)"
                  + " VALUES (?, ?, ?, ?, ?, ?)")
          .params(u, kind, title, body, url, now)
          .update();
    }
  }

  public List<Notification> list(long userId, Long before, int limit) {
    return db.sql(
            """
            SELECT id, kind, title, body, url, created_at, read_at IS NOT NULL AS is_read
            FROM notifications WHERE user_id = ? AND (? IS NULL OR id < ?)
            ORDER BY id DESC LIMIT ?
            """)
        .params(userId, before, before, limit)
        .query(
            (rs, i) ->
                new Notification(
                    rs.getLong("id"),
                    rs.getString("kind"),
                    rs.getString("title"),
                    rs.getString("body"),
                    rs.getString("url"),
                    rs.getLong("created_at"),
                    rs.getInt("is_read") == 1))
        .list();
  }

  public int unread(long userId) {
    return db.sql("SELECT count(*) FROM notifications WHERE user_id = ? AND read_at IS NULL")
        .param(userId)
        .query(Integer.class)
        .single();
  }

  public void markRead(long userId, Collection<Long> ids, long now) {
    for (Long id : ids) {
      db.sql(
              "UPDATE notifications SET read_at = ? WHERE id = ? AND user_id = ?"
                  + " AND read_at IS NULL")
          .params(now, id, userId)
          .update();
    }
  }

  public void markAllRead(long userId, long now) {
    db.sql("UPDATE notifications SET read_at = ? WHERE user_id = ? AND read_at IS NULL")
        .params(now, userId)
        .update();
  }

  /** Уборка: уведомления старше срока хранения. */
  public int deleteOlderThan(long cutoff) {
    return db.sql("DELETE FROM notifications WHERE created_at < ?").param(cutoff).update();
  }
}
