-- Пересдача (1.0.1) – отдельный вид пары. Список видов задан CHECK – в SQLite его не изменить,
-- поэтому таблица пересобирается (как materials в V23): те же id, индексы и триггеры – как в V21.
-- На пары ссылаются задания и материалы (ON DELETE SET NULL): DROP обнулил бы их lesson_id,
-- поэтому связи запоминаются и возвращаются. Триггеры журнала снимаются до DROP – иначе каждая
-- пара попала бы в журнал как удалённая.

CREATE TEMP TABLE keep_homework AS SELECT id, lesson_id FROM homework WHERE lesson_id IS NOT NULL;
CREATE TEMP TABLE keep_materials AS SELECT id, lesson_id FROM materials WHERE lesson_id IS NOT NULL;

DROP TRIGGER sync_lessons_insert;
DROP TRIGGER sync_lessons_update;
DROP TRIGGER sync_lessons_delete;

CREATE TABLE lessons_new (
  id         INTEGER PRIMARY KEY,
  group_id   INTEGER NOT NULL REFERENCES study_groups (id) ON DELETE CASCADE,
  subject_id INTEGER REFERENCES subjects (id) ON DELETE SET NULL,
  title      TEXT    NOT NULL,
  kind       TEXT    NOT NULL DEFAULT 'other'
                     CHECK (kind IN ('lecture', 'practice', 'seminar', 'lab', 'consult', 'credit',
                                     'exam', 'retake', 'other')),
  starts_at  INTEGER NOT NULL,
  ends_at    INTEGER NOT NULL,
  place      TEXT    NOT NULL DEFAULT '',
  teacher    TEXT    NOT NULL DEFAULT '',
  note       TEXT    NOT NULL DEFAULT '',
  source     TEXT,
  created_by INTEGER REFERENCES users (id) ON DELETE SET NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  cancelled  INTEGER NOT NULL DEFAULT 0 CHECK (cancelled IN (0, 1))
) STRICT;

INSERT INTO lessons_new (id, group_id, subject_id, title, kind, starts_at, ends_at, place, teacher,
                         note, source, created_by, created_at, updated_at, cancelled)
SELECT id, group_id, subject_id, title, kind, starts_at, ends_at, place, teacher,
       note, source, created_by, created_at, updated_at, cancelled
FROM lessons;

PRAGMA legacy_alter_table = ON;
DROP TABLE lessons;
ALTER TABLE lessons_new RENAME TO lessons;
PRAGMA legacy_alter_table = OFF;

CREATE INDEX lessons_group_time ON lessons (group_id, starts_at);
CREATE INDEX lessons_subject_time ON lessons (subject_id, starts_at);
CREATE UNIQUE INDEX lessons_source ON lessons (group_id, source) WHERE source IS NOT NULL;

UPDATE homework SET lesson_id = (SELECT k.lesson_id FROM keep_homework k WHERE k.id = homework.id)
WHERE id IN (SELECT id FROM keep_homework) AND lesson_id IS NULL;
UPDATE materials SET lesson_id = (SELECT k.lesson_id FROM keep_materials k WHERE k.id = materials.id)
WHERE id IN (SELECT id FROM keep_materials) AND lesson_id IS NULL;
DROP TABLE keep_homework;
DROP TABLE keep_materials;

CREATE TRIGGER sync_lessons_insert AFTER INSERT ON lessons BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('lesson', new.id);
END;

CREATE TRIGGER sync_lessons_update AFTER UPDATE ON lessons BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('lesson', new.id);
END;

CREATE TRIGGER sync_lessons_delete AFTER DELETE ON lessons BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('lesson', old.id);
END;
