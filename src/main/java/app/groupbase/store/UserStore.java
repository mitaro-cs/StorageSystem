package app.groupbase.store;

import app.groupbase.auth.InstanceRole;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserStore {

  static final RowMapper<User> MAPPER =
      (rs, i) ->
          new User(
              rs.getLong("id"),
              rs.getString("username"),
              rs.getString("display_name"),
              rs.getString("password_hash"),
              Rows.bool(rs, "must_change_password"),
              rs.getString("instance_role") == null
                  ? null
                  : InstanceRole.of(rs.getString("instance_role")),
              User.Status.of(rs.getString("status")),
              rs.getBytes("totp_secret"),
              Rows.bool(rs, "totp_enabled"),
              Rows.longOrNull(rs, "totp_last_step"),
              rs.getInt("totp_drift"),
              rs.getString("avatar"),
              rs.getInt("failed_logins"),
              Rows.longOrNull(rs, "locked_until"),
              rs.getLong("created_at"));

  private final JdbcClient db;

  public UserStore(JdbcClient db) {
    this.db = db;
  }

  public Optional<User> find(long id) {
    return db.sql("SELECT * FROM users WHERE id = ?").param(id).query(MAPPER).optional();
  }

  public Optional<User> findByUsername(String username) {
    return db.sql("SELECT * FROM users WHERE username = ?")
        .param(username)
        .query(MAPPER)
        .optional();
  }

  public boolean usernameTaken(String username) {
    return db.sql("SELECT count(*) FROM users WHERE username = ?")
            .param(username)
            .query(Integer.class)
            .single()
        > 0;
  }

  public long count() {
    return db.sql("SELECT count(*) FROM users WHERE status != 'deleted'")
        .query(Long.class)
        .single();
  }

  public long insert(
      String username,
      String displayName,
      String passwordHash,
      boolean mustChange,
      InstanceRole role,
      User.Status status,
      long now) {
    return db.sql(
            """
            INSERT INTO users (username, display_name, password_hash, must_change_password,
                               instance_role, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
            """)
        .params(
            username,
            displayName,
            passwordHash,
            mustChange ? 1 : 0,
            role == null ? null : role.id(),
            status.id(),
            now)
        .query(Long.class)
        .single();
  }

  public void setPassword(long id, String hash, boolean mustChange) {
    db.sql(
            """
            UPDATE users SET password_hash = ?, must_change_password = ?,
              status = CASE status WHEN 'pending' THEN 'active' ELSE status END,
              failed_logins = 0, locked_until = NULL
            WHERE id = ?
            """)
        .params(hash, mustChange ? 1 : 0, id)
        .update();
  }

  public void setDisplayName(long id, String displayName) {
    db.sql("UPDATE users SET display_name = ? WHERE id = ?").params(displayName, id).update();
  }

  public void setStatus(long id, User.Status status) {
    db.sql("UPDATE users SET status = ? WHERE id = ?").params(status.id(), id).update();
  }

  public void setInstanceRole(long id, InstanceRole role) {
    db.sql("UPDATE users SET instance_role = ? WHERE id = ?")
        .params(role == null ? null : role.id(), id)
        .update();
  }

  public void setAvatar(long id, String avatar) {
    db.sql("UPDATE users SET avatar = ? WHERE id = ?").params(avatar, id).update();
  }

  public void recordFailedLogin(long id, Long lockedUntil) {
    db.sql("UPDATE users SET failed_logins = failed_logins + 1, locked_until = ? WHERE id = ?")
        .params(lockedUntil, id)
        .update();
  }

  public void resetFailedLogins(long id) {
    db.sql("UPDATE users SET failed_logins = 0, locked_until = NULL WHERE id = ?")
        .param(id)
        .update();
  }

  public void setTotpSecret(long id, byte[] sealedSecret) {
    db.sql(
            "UPDATE users SET totp_secret = ?, totp_enabled = 0, totp_last_step = NULL,"
                + " totp_drift = 0 WHERE id = ?")
        .params(sealedSecret, id)
        .update();
  }

  public void enableTotp(long id, long step, int drift) {
    db.sql("UPDATE users SET totp_enabled = 1, totp_last_step = ?, totp_drift = ? WHERE id = ?")
        .params(step, drift, id)
        .update();
  }

  public void disableTotp(long id) {
    db.sql(
            "UPDATE users SET totp_enabled = 0, totp_secret = NULL, totp_last_step = NULL,"
                + " totp_drift = 0 WHERE id = ?")
        .param(id)
        .update();
  }

  /**
   * Отмечает использованный шаг TOTP и уточняет сдвиг часов; false, если шаг уже был использован
   * (гонка).
   */
  public boolean useTotpStep(long id, long step, int drift) {
    return db.sql(
                "UPDATE users SET totp_last_step = ?, totp_drift = ? WHERE id = ?"
                    + " AND (totp_last_step IS NULL OR totp_last_step < ?)")
            .params(step, drift, id, step)
            .update()
        == 1;
  }

  /** Удаление аккаунта: персональные данные стираются, строка остаётся для подписи контента. */
  public void anonymize(long id, long now) {
    db.sql(
            """
            UPDATE users SET username = 'deleted-' || id, display_name = '', password_hash = NULL,
              must_change_password = 0, instance_role = NULL, status = 'deleted',
              totp_secret = NULL, totp_enabled = 0, totp_last_step = NULL, totp_drift = 0,
              avatar = NULL,
              failed_logins = 0, locked_until = NULL, deleted_at = ?
            WHERE id = ?
            """)
        .params(now, id)
        .update();
  }

  public List<User> listAll() {
    return db.sql("SELECT * FROM users WHERE status != 'deleted' ORDER BY display_name")
        .query(MAPPER)
        .list();
  }

  public List<User> listStaff() {
    return db.sql(
            "SELECT * FROM users WHERE instance_role IS NOT NULL AND status != 'deleted'"
                + " ORDER BY display_name")
        .query(MAPPER)
        .list();
  }
}
