-- Архивы прошлых семестров (0.9.7): предметы закончившегося семестра уходят в архив вместе –
-- под одним названием («Осенний семестр 2025»). Задания, материалы и тесты остаются у предметов.
CREATE TABLE semesters (
  id         INTEGER PRIMARY KEY,
  group_id   INTEGER NOT NULL REFERENCES study_groups(id) ON DELETE CASCADE,
  name       TEXT    NOT NULL,
  created_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX semesters_group ON semesters(group_id);

ALTER TABLE subjects ADD COLUMN semester_id INTEGER REFERENCES semesters(id) ON DELETE SET NULL;
