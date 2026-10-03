-- Материалы (0.6): кроме файлов и ссылок — сообщения (kind = 'note': текст в description,
-- например «Билеты к экзамену: …» или вставленное из чата), и закрепление сверху (pinned_at).
-- Список видов задан CHECK — в SQLite его не изменить, поэтому таблица пересобирается: те же
-- id (поиск хранит rowid = id * 4 + 2), индексы и триггеры — как в V4, V6, V9 и V21. На таблицу
-- никто не ссылается, DROP её строк не трогает.

CREATE TABLE materials_new (
  id          INTEGER PRIMARY KEY,
  subject_id  INTEGER NOT NULL REFERENCES subjects (id) ON DELETE CASCADE,
  folder_id   INTEGER REFERENCES folders (id) ON DELETE CASCADE,
  kind        TEXT    NOT NULL CHECK (kind IN ('file', 'link', 'note')),
  title       TEXT    NOT NULL,
  description TEXT    NOT NULL DEFAULT '',
  url         TEXT,
  file_id     INTEGER REFERENCES files (id) ON DELETE SET NULL,
  author_id   INTEGER REFERENCES users (id) ON DELETE SET NULL,
  status      TEXT    NOT NULL DEFAULT 'published'
                      CHECK (status IN ('published', 'pending', 'rejected')),
  hidden      INTEGER NOT NULL DEFAULT 0,
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL,
  lesson_id   INTEGER REFERENCES lessons (id) ON DELETE SET NULL,
  pinned_at   INTEGER
) STRICT;

INSERT INTO materials_new (id, subject_id, folder_id, kind, title, description, url, file_id,
                           author_id, status, hidden, created_at, updated_at, lesson_id)
SELECT id, subject_id, folder_id, kind, title, description, url, file_id,
       author_id, status, hidden, created_at, updated_at, lesson_id
FROM materials;

-- Триггеры других таблиц (sync_subject_groups_* и др.) упоминают materials: без «старого»
-- переименования SQLite проверит их между DROP и RENAME и откажет.
PRAGMA legacy_alter_table = ON;
DROP TABLE materials;
ALTER TABLE materials_new RENAME TO materials;
PRAGMA legacy_alter_table = OFF;

CREATE INDEX materials_subject ON materials (subject_id, folder_id, status);
CREATE INDEX materials_file ON materials (file_id);
CREATE INDEX materials_lesson ON materials (lesson_id) WHERE lesson_id IS NOT NULL;

CREATE TRIGGER search_materials_ai AFTER INSERT ON materials BEGIN
  INSERT INTO search_index (rowid, title, body)
  VALUES (new.id * 4 + 2, replace(replace(new.title, 'ё', 'е'), 'Ё', 'Е'), replace(replace(
    new.description || ' ' || ifnull(new.url, '') || ' '
      || ifnull((SELECT name FROM files WHERE id = new.file_id), ''), 'ё', 'е'), 'Ё', 'Е'));
END;
CREATE TRIGGER search_materials_au AFTER UPDATE OF title, description, url, file_id ON materials BEGIN
  DELETE FROM search_index WHERE rowid = old.id * 4 + 2;
  INSERT INTO search_index (rowid, title, body)
  VALUES (new.id * 4 + 2, replace(replace(new.title, 'ё', 'е'), 'Ё', 'Е'), replace(replace(
    new.description || ' ' || ifnull(new.url, '') || ' '
      || ifnull((SELECT name FROM files WHERE id = new.file_id), ''), 'ё', 'е'), 'Ё', 'Е'));
END;
CREATE TRIGGER search_materials_ad AFTER DELETE ON materials BEGIN
  DELETE FROM search_index WHERE rowid = old.id * 4 + 2;
END;

CREATE TRIGGER sync_materials_insert AFTER INSERT ON materials BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('material', new.id);
END;
CREATE TRIGGER sync_materials_update AFTER UPDATE ON materials BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('material', new.id);
END;
CREATE TRIGGER sync_materials_delete AFTER DELETE ON materials BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('material', old.id);
END;

CREATE TRIGGER sync_lesson_materials_insert AFTER INSERT ON materials
WHEN new.lesson_id IS NOT NULL BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('lesson', new.lesson_id);
END;
CREATE TRIGGER sync_lesson_materials_update AFTER UPDATE OF lesson_id, hidden, status ON materials
BEGIN
  INSERT INTO changes (kind, ref_id)
    SELECT 'lesson', x FROM (SELECT old.lesson_id AS x UNION SELECT new.lesson_id)
    WHERE x IS NOT NULL;
END;
CREATE TRIGGER sync_lesson_materials_delete AFTER DELETE ON materials
WHEN old.lesson_id IS NOT NULL BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('lesson', old.lesson_id);
END;
