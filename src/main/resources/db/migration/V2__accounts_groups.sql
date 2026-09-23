-- Пользователи, группы, членство, права, сессии, инвайты, одноразовые ссылки, аудит.
-- Время — миллисекунды Unix (UTC).

CREATE TABLE users (
  id                   INTEGER PRIMARY KEY,
  username             TEXT    NOT NULL COLLATE NOCASE UNIQUE,
  display_name         TEXT    NOT NULL,
  password_hash        TEXT,
  must_change_password INTEGER NOT NULL DEFAULT 0,
  instance_role        TEXT CHECK (instance_role IN ('admin', 'moderator')),
  status               TEXT    NOT NULL DEFAULT 'active'
                               CHECK (status IN ('pending', 'active', 'blocked', 'deleted')),
  totp_secret          BLOB,             -- зашифрован ключом приложения
  totp_enabled         INTEGER NOT NULL DEFAULT 0,
  totp_last_step       INTEGER,          -- защита от повторного использования кода
  avatar               TEXT,             -- хеш аватара
  failed_logins        INTEGER NOT NULL DEFAULT 0,
  locked_until         INTEGER,
  created_at           INTEGER NOT NULL,
  deleted_at           INTEGER
) STRICT;

CREATE TABLE study_groups (
  id          INTEGER PRIMARY KEY,
  slug        TEXT    NOT NULL UNIQUE,
  name        TEXT    NOT NULL,
  university  TEXT    NOT NULL DEFAULT '',
  course      INTEGER,
  avatar      TEXT,
  created_at  INTEGER NOT NULL,
  archived_at INTEGER
) STRICT;

CREATE TABLE memberships (
  user_id   INTEGER NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  group_id  INTEGER NOT NULL REFERENCES study_groups (id) ON DELETE CASCADE,
  role      TEXT    NOT NULL CHECK (role IN ('headman', 'deputy', 'student')),
  joined_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, group_id)
) STRICT, WITHOUT ROWID;

CREATE INDEX memberships_group ON memberships (group_id, role);

-- Переопределения настраиваемых прав (⚙). group_id IS NULL — значение для всего инстанса.
CREATE TABLE group_permission_overrides (
  group_id   INTEGER REFERENCES study_groups (id) ON DELETE CASCADE,
  role       TEXT    NOT NULL,
  permission TEXT    NOT NULL,
  allowed    INTEGER NOT NULL,
  updated_by INTEGER REFERENCES users (id) ON DELETE SET NULL,
  updated_at INTEGER NOT NULL
) STRICT;

CREATE UNIQUE INDEX group_permission_overrides_key
  ON group_permission_overrides (ifnull(group_id, 0), role, permission);

-- В БД только SHA-256 токена сессии.
CREATE TABLE sessions (
  token_hash   BLOB    PRIMARY KEY,
  user_id      INTEGER NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  created_at   INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  expires_at   INTEGER NOT NULL
) STRICT, WITHOUT ROWID;

CREATE INDEX sessions_user ON sessions (user_id);
CREATE INDEX sessions_expires ON sessions (expires_at);

CREATE TABLE invites (
  id         INTEGER PRIMARY KEY,
  token_hash BLOB    NOT NULL UNIQUE,
  group_id   INTEGER NOT NULL REFERENCES study_groups (id) ON DELETE CASCADE,
  role       TEXT    NOT NULL CHECK (role IN ('headman', 'deputy', 'student')),
  max_uses   INTEGER,                   -- NULL — без ограничения
  uses       INTEGER NOT NULL DEFAULT 0,
  expires_at INTEGER NOT NULL,
  note       TEXT    NOT NULL DEFAULT '',
  created_by INTEGER REFERENCES users (id) ON DELETE SET NULL,
  created_at INTEGER NOT NULL,
  revoked_at INTEGER
) STRICT;

CREATE INDEX invites_group ON invites (group_id);

-- Одноразовые ссылки: активация аккаунта и сброс пароля.
CREATE TABLE user_tokens (
  token_hash BLOB    PRIMARY KEY,
  user_id    INTEGER NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  purpose    TEXT    NOT NULL CHECK (purpose IN ('activate', 'reset')),
  created_by INTEGER REFERENCES users (id) ON DELETE SET NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  used_at    INTEGER
) STRICT, WITHOUT ROWID;

CREATE INDEX user_tokens_user ON user_tokens (user_id);

-- IP хранится 30 дней, затем обнуляется фоновой задачей.
CREATE TABLE audit_log (
  id          INTEGER PRIMARY KEY,
  at          INTEGER NOT NULL,
  actor_id    INTEGER REFERENCES users (id) ON DELETE SET NULL,
  group_id    INTEGER REFERENCES study_groups (id) ON DELETE SET NULL,
  action      TEXT    NOT NULL,
  target_type TEXT,
  target_id   INTEGER,
  details     TEXT,
  ip          TEXT
) STRICT;

CREATE INDEX audit_log_group_at ON audit_log (group_id, at);
CREATE INDEX audit_log_at ON audit_log (at);

INSERT INTO instance_settings (key, value) VALUES
  ('instance.mode', 'single'),
  ('accounts.direct', 'true'),
  ('accounts.invites', 'true');
