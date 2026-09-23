-- Предметы (в т.ч. общие для нескольких групп), новости, домашние задания, комментарии.

CREATE TABLE subjects (
  id          INTEGER PRIMARY KEY,
  name        TEXT    NOT NULL,
  teacher     TEXT    NOT NULL DEFAULT '',
  color       TEXT    NOT NULL DEFAULT '#6b7280',
  avatar      TEXT,
  created_by  INTEGER REFERENCES users (id) ON DELETE SET NULL,
  created_at  INTEGER NOT NULL,
  archived_at INTEGER
) STRICT;

CREATE TABLE subject_groups (
  subject_id INTEGER NOT NULL REFERENCES subjects (id) ON DELETE CASCADE,
  group_id   INTEGER NOT NULL REFERENCES study_groups (id) ON DELETE CASCADE,
  linked_at  INTEGER NOT NULL,
  PRIMARY KEY (subject_id, group_id)
) STRICT, WITHOUT ROWID;

CREATE INDEX subject_groups_group ON subject_groups (group_id);

-- Запрос старосты связать предмет с чужой группой; решает староста второй группы.
CREATE TABLE subject_link_requests (
  id            INTEGER PRIMARY KEY,
  subject_id    INTEGER NOT NULL REFERENCES subjects (id) ON DELETE CASCADE,
  from_group_id INTEGER NOT NULL REFERENCES study_groups (id) ON DELETE CASCADE,
  to_group_id   INTEGER NOT NULL REFERENCES study_groups (id) ON DELETE CASCADE,
  requested_by  INTEGER REFERENCES users (id) ON DELETE SET NULL,
  status        TEXT    NOT NULL DEFAULT 'pending'
                        CHECK (status IN ('pending', 'accepted', 'rejected', 'cancelled')),
  created_at    INTEGER NOT NULL,
  decided_by    INTEGER REFERENCES users (id) ON DELETE SET NULL,
  decided_at    INTEGER
) STRICT;

CREATE UNIQUE INDEX subject_link_requests_pending
  ON subject_link_requests (subject_id, to_group_id) WHERE status = 'pending';

CREATE TABLE subject_pins (
  user_id    INTEGER NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  subject_id INTEGER NOT NULL REFERENCES subjects (id) ON DELETE CASCADE,
  pinned_at  INTEGER NOT NULL,
  PRIMARY KEY (user_id, subject_id)
) STRICT, WITHOUT ROWID;

CREATE TABLE posts (
  id         INTEGER PRIMARY KEY,
  author_id  INTEGER REFERENCES users (id) ON DELETE SET NULL,
  subject_id INTEGER REFERENCES subjects (id) ON DELETE SET NULL,
  title      TEXT    NOT NULL,
  body_md    TEXT    NOT NULL DEFAULT '',
  body_html  TEXT    NOT NULL DEFAULT '',
  pinned     INTEGER NOT NULL DEFAULT 0,
  urgent     INTEGER NOT NULL DEFAULT 0,
  hidden     INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
) STRICT;

CREATE INDEX posts_subject ON posts (subject_id, id);

CREATE TABLE post_targets (
  post_id  INTEGER NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
  group_id INTEGER NOT NULL REFERENCES study_groups (id) ON DELETE CASCADE,
  PRIMARY KEY (post_id, group_id)
) STRICT, WITHOUT ROWID;

CREATE INDEX post_targets_group ON post_targets (group_id, post_id);

CREATE TABLE homework (
  id         INTEGER PRIMARY KEY,
  subject_id INTEGER NOT NULL REFERENCES subjects (id) ON DELETE CASCADE,
  author_id  INTEGER REFERENCES users (id) ON DELETE SET NULL,
  title      TEXT    NOT NULL,
  body_md    TEXT    NOT NULL DEFAULT '',
  body_html  TEXT    NOT NULL DEFAULT '',
  due_at     INTEGER NOT NULL,
  hidden     INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
) STRICT;

CREATE INDEX homework_due ON homework (due_at);
CREATE INDEX homework_subject ON homework (subject_id, due_at);

CREATE TABLE homework_targets (
  homework_id INTEGER NOT NULL REFERENCES homework (id) ON DELETE CASCADE,
  group_id    INTEGER NOT NULL REFERENCES study_groups (id) ON DELETE CASCADE,
  PRIMARY KEY (homework_id, group_id)
) STRICT, WITHOUT ROWID;

CREATE INDEX homework_targets_group ON homework_targets (group_id, homework_id);

-- Личная отметка «выполнено»: видна только самому пользователю.
CREATE TABLE homework_done (
  user_id     INTEGER NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  homework_id INTEGER NOT NULL REFERENCES homework (id) ON DELETE CASCADE,
  done_at     INTEGER NOT NULL,
  PRIMARY KEY (user_id, homework_id)
) STRICT, WITHOUT ROWID;

CREATE TABLE comments (
  id          INTEGER PRIMARY KEY,
  target_type TEXT    NOT NULL CHECK (target_type IN ('post', 'homework', 'material')),
  target_id   INTEGER NOT NULL,
  author_id   INTEGER REFERENCES users (id) ON DELETE SET NULL,
  body_md     TEXT    NOT NULL,
  body_html   TEXT    NOT NULL,
  hidden      INTEGER NOT NULL DEFAULT 0,
  created_at  INTEGER NOT NULL
) STRICT;

CREATE INDEX comments_target ON comments (target_type, target_id, id);
