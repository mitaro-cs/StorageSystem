-- Расписание пар (0.4.12): занятия группы — из файла календаря (.ics) или вручную. Задание и
-- материал могут относиться к паре (lesson_id): «сдать к этой паре», «слайды этой лекции».

CREATE TABLE lessons (
  id         INTEGER PRIMARY KEY,
  group_id   INTEGER NOT NULL REFERENCES study_groups (id) ON DELETE CASCADE,
  subject_id INTEGER REFERENCES subjects (id) ON DELETE SET NULL,
  title      TEXT    NOT NULL,
  kind       TEXT    NOT NULL DEFAULT 'other'
                     CHECK (kind IN ('lecture', 'practice', 'seminar', 'lab', 'consult', 'credit',
                                     'exam', 'other')),
  starts_at  INTEGER NOT NULL,
  ends_at    INTEGER NOT NULL,
  place      TEXT    NOT NULL DEFAULT '',
  teacher    TEXT    NOT NULL DEFAULT '',
  -- Тема занятия или заметка: «Лекция 5. Производные», «принести калькулятор».
  note       TEXT    NOT NULL DEFAULT '',
  -- Событие из файла (UID, у повторов — и начало по серии): повторная загрузка того же файла
  -- обновляет занятие, а не дублирует. NULL — добавлено вручную.
  source     TEXT,
  created_by INTEGER REFERENCES users (id) ON DELETE SET NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
) STRICT;

CREATE INDEX lessons_group_time ON lessons (group_id, starts_at);
CREATE INDEX lessons_subject_time ON lessons (subject_id, starts_at);
CREATE UNIQUE INDEX lessons_source ON lessons (group_id, source) WHERE source IS NOT NULL;

ALTER TABLE homework ADD COLUMN lesson_id INTEGER REFERENCES lessons (id) ON DELETE SET NULL;
ALTER TABLE materials ADD COLUMN lesson_id INTEGER REFERENCES lessons (id) ON DELETE SET NULL;
CREATE INDEX homework_lesson ON homework (lesson_id) WHERE lesson_id IS NOT NULL;
CREATE INDEX materials_lesson ON materials (lesson_id) WHERE lesson_id IS NOT NULL;

-- Офлайн-копия и живые обновления: занятия идут тем же журналом, что задания и новости. У пары
-- видно, сколько к ней заданий и материалов, — их появление тоже меняет пару.
CREATE TRIGGER sync_lessons_insert AFTER INSERT ON lessons BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('lesson', new.id);
END;

CREATE TRIGGER sync_lessons_update AFTER UPDATE ON lessons BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('lesson', new.id);
END;

CREATE TRIGGER sync_lessons_delete AFTER DELETE ON lessons BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('lesson', old.id);
END;

CREATE TRIGGER sync_lesson_homework_insert AFTER INSERT ON homework
WHEN new.lesson_id IS NOT NULL BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('lesson', new.lesson_id);
END;

CREATE TRIGGER sync_lesson_homework_update AFTER UPDATE OF lesson_id, hidden ON homework BEGIN
  INSERT INTO changes (kind, ref_id)
    SELECT 'lesson', x FROM (SELECT old.lesson_id AS x UNION SELECT new.lesson_id)
    WHERE x IS NOT NULL;
END;

CREATE TRIGGER sync_lesson_homework_delete AFTER DELETE ON homework
WHEN old.lesson_id IS NOT NULL BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('lesson', old.lesson_id);
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
