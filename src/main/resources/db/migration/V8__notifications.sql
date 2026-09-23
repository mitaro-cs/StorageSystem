-- Уведомления в приложении, подписки Web Push, настройки уведомлений и отправленные напоминания.

CREATE TABLE notifications (
  id         INTEGER PRIMARY KEY,
  user_id    INTEGER NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  kind       TEXT    NOT NULL,
  title      TEXT    NOT NULL,
  body       TEXT    NOT NULL DEFAULT '',
  url        TEXT    NOT NULL,
  created_at INTEGER NOT NULL,
  read_at    INTEGER
) STRICT;

CREATE INDEX notifications_user ON notifications (user_id, id);
CREATE INDEX notifications_unread ON notifications (user_id) WHERE read_at IS NULL;

-- Адрес службы push уникален для браузера; устройство подписано кратко («Chrome, Android»).
CREATE TABLE push_subscriptions (
  id         INTEGER PRIMARY KEY,
  user_id    INTEGER NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  endpoint   TEXT    NOT NULL UNIQUE,
  p256dh     TEXT    NOT NULL,
  auth       TEXT    NOT NULL,
  device     TEXT    NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  last_ok_at INTEGER,
  failures   INTEGER NOT NULL DEFAULT 0
) STRICT;

CREATE INDEX push_subscriptions_user ON push_subscriptions (user_id);

-- Что присылать push-уведомлением. Нет строки — значения по умолчанию.
CREATE TABLE notification_prefs (
  user_id     INTEGER PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
  homework    INTEGER NOT NULL DEFAULT 1,
  news        TEXT    NOT NULL DEFAULT 'all' CHECK (news IN ('all', 'urgent', 'none')),
  materials   INTEGER NOT NULL DEFAULT 0,
  reminders   INTEGER NOT NULL DEFAULT 1,
  digest      INTEGER NOT NULL DEFAULT 0,
  digest_at   INTEGER NOT NULL DEFAULT 480 CHECK (digest_at BETWEEN 0 AND 1439),
  digest_last TEXT
) STRICT;

CREATE TABLE homework_reminders (
  user_id     INTEGER NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  homework_id INTEGER NOT NULL REFERENCES homework (id) ON DELETE CASCADE,
  sent_at     INTEGER NOT NULL,
  PRIMARY KEY (user_id, homework_id)
) STRICT, WITHOUT ROWID;
