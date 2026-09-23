package app.groupbase.store;

import app.groupbase.auth.GroupRole;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InviteStore {

  static final RowMapper<Invite> MAPPER =
      (rs, i) ->
          new Invite(
              rs.getLong("id"),
              rs.getLong("group_id"),
              GroupRole.of(rs.getString("role")),
              Rows.intOrNull(rs, "max_uses"),
              rs.getInt("uses"),
              rs.getLong("expires_at"),
              rs.getString("note"),
              Rows.longOrNull(rs, "created_by"),
              rs.getLong("created_at"),
              Rows.longOrNull(rs, "revoked_at"));

  private final JdbcClient db;

  public InviteStore(JdbcClient db) {
    this.db = db;
  }

  public long insert(
      byte[] tokenHash,
      long groupId,
      GroupRole role,
      Integer maxUses,
      long expiresAt,
      String note,
      long createdBy,
      long now) {
    return db.sql(
            """
            INSERT INTO invites (token_hash, group_id, role, max_uses, expires_at, note,
                                 created_by, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
            """)
        .params(tokenHash, groupId, role.id(), maxUses, expiresAt, note, createdBy, now)
        .query(Long.class)
        .single();
  }

  public Optional<Invite> findByHash(byte[] tokenHash) {
    return db.sql("SELECT * FROM invites WHERE token_hash = ?")
        .param(tokenHash)
        .query(MAPPER)
        .optional();
  }

  public Optional<Invite> find(long id) {
    return db.sql("SELECT * FROM invites WHERE id = ?").param(id).query(MAPPER).optional();
  }

  public List<Invite> list(long groupId) {
    return db.sql("SELECT * FROM invites WHERE group_id = ? ORDER BY created_at DESC")
        .param(groupId)
        .query(MAPPER)
        .list();
  }

  /** Атомарно тратит одно использование. false — инвайт исчерпан, отозван или истёк. */
  public boolean consume(long id, long now) {
    return db.sql(
                """
                UPDATE invites SET uses = uses + 1
                WHERE id = ? AND revoked_at IS NULL AND expires_at > ?
                  AND (max_uses IS NULL OR uses < max_uses)
                """)
            .params(id, now)
            .update()
        == 1;
  }

  public void revoke(long id, long now) {
    db.sql("UPDATE invites SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL")
        .params(now, id)
        .update();
  }
}
