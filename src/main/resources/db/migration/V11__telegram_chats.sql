-- Чаты в Telegram: закреплённые у группы (показываются на главной рядом с быстрыми действиями)
-- и чат предмета (кнопка на странице предмета).
CREATE TABLE group_chats (
  id INTEGER PRIMARY KEY,
  group_id INTEGER NOT NULL REFERENCES study_groups(id) ON DELETE CASCADE,
  title TEXT NOT NULL,
  url TEXT NOT NULL,
  position INTEGER NOT NULL DEFAULT 0,
  created_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX group_chats_group ON group_chats (group_id, position);

ALTER TABLE subjects ADD COLUMN chat_url TEXT;
