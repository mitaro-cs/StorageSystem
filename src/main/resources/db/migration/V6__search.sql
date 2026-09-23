-- Полнотекстовый поиск (FTS5). rowid кодирует источник: id * 4 + вид
-- (0 — новость, 1 — задание, 2 — материал, 3 — предмет), поэтому триггеры удаляют строку по rowid
-- без полного просмотра. «ё» приводится к «е» (FTS5 сам этого не делает), чтобы «расчёт» находился
-- по «расчет».

CREATE VIRTUAL TABLE search_index USING fts5(
  title,
  body,
  tokenize = 'unicode61 remove_diacritics 2'
);

CREATE TRIGGER search_posts_ai AFTER INSERT ON posts BEGIN
  INSERT INTO search_index (rowid, title, body)
  VALUES (new.id * 4, replace(replace(new.title, 'ё', 'е'), 'Ё', 'Е'), replace(replace(new.body_md, 'ё', 'е'), 'Ё', 'Е'));
END;
CREATE TRIGGER search_posts_au AFTER UPDATE OF title, body_md ON posts BEGIN
  DELETE FROM search_index WHERE rowid = old.id * 4;
  INSERT INTO search_index (rowid, title, body)
  VALUES (new.id * 4, replace(replace(new.title, 'ё', 'е'), 'Ё', 'Е'), replace(replace(new.body_md, 'ё', 'е'), 'Ё', 'Е'));
END;
CREATE TRIGGER search_posts_ad AFTER DELETE ON posts BEGIN
  DELETE FROM search_index WHERE rowid = old.id * 4;
END;

CREATE TRIGGER search_homework_ai AFTER INSERT ON homework BEGIN
  INSERT INTO search_index (rowid, title, body)
  VALUES (new.id * 4 + 1, replace(replace(new.title, 'ё', 'е'), 'Ё', 'Е'), replace(replace(new.body_md, 'ё', 'е'), 'Ё', 'Е'));
END;
CREATE TRIGGER search_homework_au AFTER UPDATE OF title, body_md ON homework BEGIN
  DELETE FROM search_index WHERE rowid = old.id * 4 + 1;
  INSERT INTO search_index (rowid, title, body)
  VALUES (new.id * 4 + 1, replace(replace(new.title, 'ё', 'е'), 'Ё', 'Е'), replace(replace(new.body_md, 'ё', 'е'), 'Ё', 'Е'));
END;
CREATE TRIGGER search_homework_ad AFTER DELETE ON homework BEGIN
  DELETE FROM search_index WHERE rowid = old.id * 4 + 1;
END;

-- Материал: название, описание, ссылка и имя файла.
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

CREATE TRIGGER search_subjects_ai AFTER INSERT ON subjects BEGIN
  INSERT INTO search_index (rowid, title, body)
  VALUES (new.id * 4 + 3, replace(replace(new.name, 'ё', 'е'), 'Ё', 'Е'), replace(replace(new.teacher, 'ё', 'е'), 'Ё', 'Е'));
END;
CREATE TRIGGER search_subjects_au AFTER UPDATE OF name, teacher ON subjects BEGIN
  DELETE FROM search_index WHERE rowid = old.id * 4 + 3;
  INSERT INTO search_index (rowid, title, body)
  VALUES (new.id * 4 + 3, replace(replace(new.name, 'ё', 'е'), 'Ё', 'Е'), replace(replace(new.teacher, 'ё', 'е'), 'Ё', 'Е'));
END;
CREATE TRIGGER search_subjects_ad AFTER DELETE ON subjects BEGIN
  DELETE FROM search_index WHERE rowid = old.id * 4 + 3;
END;

-- То, что уже есть в базе.
INSERT INTO search_index (rowid, title, body)
  SELECT id * 4, replace(replace(title, 'ё', 'е'), 'Ё', 'Е'), replace(replace(body_md, 'ё', 'е'), 'Ё', 'Е') FROM posts;
INSERT INTO search_index (rowid, title, body)
  SELECT id * 4 + 1, replace(replace(title, 'ё', 'е'), 'Ё', 'Е'), replace(replace(body_md, 'ё', 'е'), 'Ё', 'Е') FROM homework;
INSERT INTO search_index (rowid, title, body)
  SELECT m.id * 4 + 2, replace(replace(m.title, 'ё', 'е'), 'Ё', 'Е'), replace(replace(
    m.description || ' ' || ifnull(m.url, '') || ' ' || ifnull(f.name, ''), 'ё', 'е'), 'Ё', 'Е')
  FROM materials m LEFT JOIN files f ON f.id = m.file_id;
INSERT INTO search_index (rowid, title, body)
  SELECT id * 4 + 3, replace(replace(name, 'ё', 'е'), 'Ё', 'Е'), replace(replace(teacher, 'ё', 'е'), 'Ё', 'Е') FROM subjects;
