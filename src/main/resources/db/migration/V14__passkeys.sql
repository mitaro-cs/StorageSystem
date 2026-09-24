-- Вход по отпечатку или лицу (WebAuthn, passkeys). Открытый ключ — SubjectPublicKeyInfo (DER),
-- как его отдаёт браузер (AuthenticatorAttestationResponse.getPublicKey()).
CREATE TABLE passkeys (
  id            INTEGER PRIMARY KEY,
  user_id       INTEGER NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  credential_id BLOB    NOT NULL UNIQUE,
  public_key    BLOB    NOT NULL,
  -- COSE: -7 — ES256, -8 — EdDSA, -257 — RS256
  algorithm     INTEGER NOT NULL CHECK (algorithm IN (-7, -8, -257)),
  sign_count    INTEGER NOT NULL DEFAULT 0,
  -- Домен сайта, для которого создан ключ: с другого адреса этот ключ не подойдёт.
  rp_id         TEXT    NOT NULL,
  name          TEXT    NOT NULL,
  created_at    INTEGER NOT NULL,
  last_used_at  INTEGER
) STRICT;

CREATE INDEX passkeys_user ON passkeys (user_id);
