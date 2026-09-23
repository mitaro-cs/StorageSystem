-- Резервные коды двухфакторного входа: одноразовые, в базе только SHA-256.

CREATE TABLE totp_recovery_codes (
  id        INTEGER PRIMARY KEY,
  user_id   INTEGER NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  code_hash TEXT    NOT NULL,
  used_at   INTEGER
) STRICT;

CREATE INDEX totp_recovery_codes_user ON totp_recovery_codes (user_id, code_hash);
