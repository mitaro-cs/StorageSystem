package app.groupbase.store;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SessionStore {

  public record Row(
      byte[] tokenHash, long userId, long createdAt, long lastSeenAt, long expiresAt) {}

  private final JdbcClient db;

  public SessionStore(JdbcClient db) {
    this.db = db;
  }

  public void insert(byte[] tokenHash, long userId, long now, long expiresAt) {
    db.sql(
            "INSERT INTO sessions (token_hash, user_id, created_at, last_seen_at, expires_at)"
                + " VALUES (?, ?, ?, ?, ?)")
        .params(tokenHash, userId, now, now, expiresAt)
        .update();
  }

  public Optional<Row> find(byte[] tokenHash) {
    return db.sql("SELECT * FROM sessions WHERE token_hash = ?")
        .param(tokenHash)
        .query(
            (rs, i) ->
                new Row(
                    rs.getBytes("token_hash"),
                    rs.getLong("user_id"),
                    rs.getLong("created_at"),
                    rs.getLong("last_seen_at"),
                    rs.getLong("expires_at")))
        .optional();
  }

  public void touch(byte[] tokenHash, long now, long expiresAt) {
    db.sql("UPDATE sessions SET last_seen_at = ?, expires_at = ? WHERE token_hash = ?")
        .params(now, expiresAt, tokenHash)
        .update();
  }

  public void delete(byte[] tokenHash) {
    db.sql("DELETE FROM sessions WHERE token_hash = ?").param(tokenHash).update();
  }

  public void deleteForUser(long userId) {
    db.sql("DELETE FROM sessions WHERE user_id = ?").param(userId).update();
  }

  /** Все сессии пользователя, кроме текущей (например, после смены пароля). */
  public void deleteOthers(long userId, byte[] keep) {
    db.sql("DELETE FROM sessions WHERE user_id = ? AND token_hash != ?")
        .params(userId, keep)
        .update();
  }

  public int deleteExpired(long now) {
    return db.sql("DELETE FROM sessions WHERE expires_at < ?").param(now).update();
  }
}
