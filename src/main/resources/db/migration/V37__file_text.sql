-- Поиск по содержимому файлов (1.0.2): текст PDF, документов Word, презентаций, конспектов .md.
-- Достаёт его фоновая задача (files/FileText) – здесь только итог по каждому файлу и индекс FTS5
-- (rowid = files.id; «ё» приведена к «е», как в search_index).
CREATE TABLE file_text (
  file_id      INTEGER PRIMARY KEY REFERENCES files (id) ON DELETE CASCADE,
  status       TEXT    NOT NULL CHECK (status IN ('ok', 'empty', 'skipped', 'failed')),
  chars        INTEGER NOT NULL DEFAULT 0,
  extracted_at INTEGER NOT NULL
) STRICT;

CREATE VIRTUAL TABLE file_search USING fts5(
  body,
  tokenize = 'unicode61 remove_diacritics 2'
);

CREATE TRIGGER file_search_files_ad AFTER DELETE ON files BEGIN
  DELETE FROM file_search WHERE rowid = old.id;
END;
