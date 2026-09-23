package app.groupbase.notify;

import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Подписки браузеров на Web Push. */
@Repository
public class PushSubscriptions {

  public record Sub(long id, long userId, String endpoint, String p256dh, String auth) {}

  public record Device(long id, String device, long createdAt, Long lastOkAt) {}

  /** После стольких неудач подряд подписка считается мёртвой и удаляется. */
  static final int MAX_FAILURES = 5;

  private final JdbcClient db;

  public PushSubscriptions(JdbcClient db) {
    this.db = db;
  }

  /** Браузер может переподписаться или смениться пользователь — адрес переходит к нему. */
  public void save(
      long userId, String endpoint, String p256dh, String auth, String device, long now) {
    db.sql(
            """
            INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth, device, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (endpoint) DO UPDATE SET user_id = excluded.user_id,
              p256dh = excluded.p256dh, auth = excluded.auth, device = excluded.device,
              failures = 0
            """)
        .params(userId, endpoint, p256dh, auth, device, now)
        .update();
  }

  public void delete(long userId, String endpoint) {
    db.sql("DELETE FROM push_subscriptions WHERE user_id = ? AND endpoint = ?")
        .params(userId, endpoint)
        .update();
  }

  public boolean deleteDevice(long userId, long id) {
    return db.sql("DELETE FROM push_subscriptions WHERE user_id = ? AND id = ?")
            .params(userId, id)
            .update()
        == 1;
  }

  void deleteById(long id) {
    db.sql("DELETE FROM push_subscriptions WHERE id = ?").param(id).update();
  }

  public List<Sub> forUsers(Collection<Long> users) {
    if (users.isEmpty()) {
      return List.of();
    }
    return db.sql(
            "SELECT id, user_id, endpoint, p256dh, auth FROM push_subscriptions"
                + " WHERE user_id IN (:u)")
        .param("u", List.copyOf(users))
        .query(
            (rs, i) ->
                new Sub(
                    rs.getLong("id"),
                    rs.getLong("user_id"),
                    rs.getString("endpoint"),
                    rs.getString("p256dh"),
                    rs.getString("auth")))
        .list();
  }

  public List<Device> devices(long userId) {
    return db.sql(
            "SELECT id, device, created_at, last_ok_at FROM push_subscriptions"
                + " WHERE user_id = ? ORDER BY id")
        .param(userId)
        .query(
            (rs, i) -> {
              long ok = rs.getLong("last_ok_at");
              return new Device(
                  rs.getLong("id"),
                  rs.getString("device"),
                  rs.getLong("created_at"),
                  rs.wasNull() ? null : ok);
            })
        .list();
  }

  void ok(long id, long now) {
    db.sql("UPDATE push_subscriptions SET last_ok_at = ?, failures = 0 WHERE id = ?")
        .params(now, id)
        .update();
  }

  void failed(long id) {
    db.sql("UPDATE push_subscriptions SET failures = failures + 1 WHERE id = ?").param(id).update();
    db.sql("DELETE FROM push_subscriptions WHERE id = ? AND failures >= ?")
        .params(id, MAX_FAILURES)
        .update();
  }
}
