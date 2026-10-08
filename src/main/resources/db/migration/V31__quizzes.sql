-- Тесты онлайн (0.9.7): тест принадлежит предмету, вопросы – JSON (варианты ответа, верные
-- ответы, баллы), попытки студентов – отдельно, проверяет сервер.
CREATE TABLE quizzes (
  id           INTEGER PRIMARY KEY,
  subject_id   INTEGER NOT NULL REFERENCES subjects(id) ON DELETE CASCADE,
  title        TEXT    NOT NULL,
  description  TEXT    NOT NULL DEFAULT '',
  questions    TEXT    NOT NULL DEFAULT '[]',
  time_limit   INTEGER,                    -- минут на попытку, NULL – без ограничения
  attempts     INTEGER NOT NULL DEFAULT 1, -- попыток на человека, 0 – без ограничения
  show_answers INTEGER NOT NULL DEFAULT 1, -- после попытки показать верные ответы
  published    INTEGER NOT NULL DEFAULT 0,
  closes_at    INTEGER,                    -- после этого времени новые попытки не начать
  created_by   INTEGER REFERENCES users(id) ON DELETE SET NULL,
  created_at   INTEGER NOT NULL,
  updated_at   INTEGER NOT NULL
);
CREATE INDEX quizzes_subject ON quizzes(subject_id);

CREATE TABLE quiz_attempts (
  id          INTEGER PRIMARY KEY,
  quiz_id     INTEGER NOT NULL REFERENCES quizzes(id) ON DELETE CASCADE,
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  started_at  INTEGER NOT NULL,
  finished_at INTEGER,
  answers     TEXT    NOT NULL DEFAULT '[]',
  score       REAL,
  max_score   REAL
);
CREATE INDEX quiz_attempts_quiz ON quiz_attempts(quiz_id, user_id);
