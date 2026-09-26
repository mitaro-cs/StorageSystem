-- Фото и файлы в новостях — как вложения у заданий: расписание снимком, приказ, методичка.
CREATE TABLE post_attachments (
  post_id  INTEGER NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
  file_id  INTEGER NOT NULL REFERENCES files (id) ON DELETE CASCADE,
  position INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (post_id, file_id)
) STRICT, WITHOUT ROWID;

CREATE INDEX post_attachments_file ON post_attachments (file_id);
