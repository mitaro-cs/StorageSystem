package app.groupbase.store;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Одноразовые ссылки активации и сброса пароля. */
@Repository
public class UserTokenStore {

  public enum Purpose {
    ACTIVATE,
    RESET;

    public String id() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  public record Row(long userId, Purpose purpose, long expiresAt, Long usedAt) {}

  private final JdbcClient db;

  public UserTokenStore(JdbcClient db) {
    this.db = db;
  }

  public void insert(
      byte[] tokenHash, long userId, Purpose purpose, Long createdBy, long now, long expiresAt) {
    // Старые неиспользованные ссылки того же назначения больше не нужны.
    db.sql("DELETE FROM user_tokens WHERE user_id = ? AND purpose = ? AND used_at IS NULL")
        .params(userId, purpose.id())
        .update();
    db.sql(
            "INSERT INTO user_tokens (token_hash, user_id, purpose, created_by, created_at,"
                + " expires_at) VALUES (?, ?, ?, ?, ?, ?)")
        .params(tokenHash, userId, purpose.id(), createdBy, now, expiresAt)
        .update();
  }

  public Optional<Row> find(byte[] tokenHash) {
    return db.sql(
            "SELECT user_id, purpose, expires_at, used_at FROM user_tokens WHERE token_hash = ?")
        .param(tokenHash)
        .query(
            (rs, i) ->
                new Row(
                    rs.getLong(1),
                    Purpose.valueOf(rs.getString(2).toUpperCase(java.util.Locale.ROOT)),
                    rs.getLong(3),
                    Rows.longOrNull(rs, "used_at")))
        .optional();
  }

  /** Помечает ссылку использованной; false, если её уже использовали. */
  public boolean markUsed(byte[] tokenHash, long now) {
    return db.sql(
                "UPDATE user_tokens SET used_at = ? WHERE token_hash = ? AND used_at IS NULL"
                    + " AND expires_at > ?")
            .params(now, tokenHash, now)
            .update()
        == 1;
  }

  public void deleteForUser(long userId) {
    db.sql("DELETE FROM user_tokens WHERE user_id = ?").param(userId).update();
  }

  public int deleteExpired(long now) {
    return db.sql("DELETE FROM user_tokens WHERE expires_at < ?").param(now).update();
  }
}
