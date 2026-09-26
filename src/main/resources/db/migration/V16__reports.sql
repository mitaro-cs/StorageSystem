-- Жалобы участников на новость, задание, материал или комментарий. Их разбирают в «Модерации»:
-- скрыть, удалить или оставить как есть — тогда все открытые жалобы на это закрываются.

CREATE TABLE reports (
  id          INTEGER PRIMARY KEY,
  target_type TEXT    NOT NULL CHECK (target_type IN ('post', 'homework', 'material', 'comment')),
  target_id   INTEGER NOT NULL,
  reporter_id INTEGER REFERENCES users (id) ON DELETE SET NULL,
  reason      TEXT    NOT NULL DEFAULT '',
  created_at  INTEGER NOT NULL,
  resolved_at INTEGER,
  resolved_by INTEGER REFERENCES users (id) ON DELETE SET NULL,
  resolution  TEXT    CHECK (resolution IN ('hidden', 'deleted', 'dismissed'))
) STRICT;

CREATE INDEX reports_open ON reports (target_type, target_id) WHERE resolved_at IS NULL;

-- От одного человека на одно и то же — одна открытая жалоба.
CREATE UNIQUE INDEX reports_once ON reports (target_type, target_id, reporter_id)
  WHERE resolved_at IS NULL;
