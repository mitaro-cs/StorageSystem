-- Файлы (зашифрованы на диске, имя на диске — UUID), папки и материалы предметов, вложения ДЗ.

CREATE TABLE files (
  id          INTEGER PRIMARY KEY,
  uuid        TEXT    NOT NULL UNIQUE,
  name        TEXT    NOT NULL,
  mime        TEXT    NOT NULL,
  size        INTEGER NOT NULL,
  sha256      TEXT    NOT NULL,
  uploaded_by INTEGER REFERENCES users (id) ON DELETE SET NULL,
  created_at  INTEGER NOT NULL
) STRICT;

CREATE INDEX files_created ON files (created_at);

CREATE TABLE folders (
  id         INTEGER PRIMARY KEY,
  subject_id INTEGER NOT NULL REFERENCES subjects (id) ON DELETE CASCADE,
  parent_id  INTEGER REFERENCES folders (id) ON DELETE CASCADE,
  name       TEXT    NOT NULL,
  created_by INTEGER REFERENCES users (id) ON DELETE SET NULL,
  created_at INTEGER NOT NULL
) STRICT;

CREATE INDEX folders_subject ON folders (subject_id, parent_id);

CREATE TABLE materials (
  id          INTEGER PRIMARY KEY,
  subject_id  INTEGER NOT NULL REFERENCES subjects (id) ON DELETE CASCADE,
  folder_id   INTEGER REFERENCES folders (id) ON DELETE CASCADE,
  kind        TEXT    NOT NULL CHECK (kind IN ('file', 'link')),
  title       TEXT    NOT NULL,
  description TEXT    NOT NULL DEFAULT '',
  url         TEXT,
  file_id     INTEGER REFERENCES files (id) ON DELETE SET NULL,
  author_id   INTEGER REFERENCES users (id) ON DELETE SET NULL,
  status      TEXT    NOT NULL DEFAULT 'published'
                      CHECK (status IN ('published', 'pending', 'rejected')),
  hidden      INTEGER NOT NULL DEFAULT 0,
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
) STRICT;

CREATE INDEX materials_subject ON materials (subject_id, folder_id, status);
CREATE INDEX materials_file ON materials (file_id);

CREATE TABLE homework_attachments (
  homework_id INTEGER NOT NULL REFERENCES homework (id) ON DELETE CASCADE,
  file_id     INTEGER NOT NULL REFERENCES files (id) ON DELETE CASCADE,
  position    INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (homework_id, file_id)
) STRICT, WITHOUT ROWID;

CREATE INDEX homework_attachments_file ON homework_attachments (file_id);
