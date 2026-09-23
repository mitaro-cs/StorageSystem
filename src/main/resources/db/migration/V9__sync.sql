-- Журнал изменений для офлайн-синхронизации. Триггеры записывают, какой объект изменился;
-- сервер при синхронизации сам решает, что из этого видно пользователю и что у него удалить.
-- «reset» — полный пересбор для пользователя (вступил в группу, сменил роль).

CREATE TABLE changes (
  seq        INTEGER PRIMARY KEY AUTOINCREMENT,
  kind       TEXT    NOT NULL,
  ref_id     INTEGER NOT NULL,
  created_at INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER))
) STRICT;

CREATE INDEX changes_created ON changes (created_at);

CREATE TRIGGER sync_posts_insert AFTER INSERT ON posts BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('post', new.id);
END;

CREATE TRIGGER sync_posts_update AFTER UPDATE ON posts BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('post', new.id);
END;

CREATE TRIGGER sync_posts_delete AFTER DELETE ON posts BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('post', old.id);
END;

CREATE TRIGGER sync_post_targets_insert AFTER INSERT ON post_targets BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('post', new.post_id);
END;

CREATE TRIGGER sync_post_targets_delete AFTER DELETE ON post_targets BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('post', old.post_id);
END;

CREATE TRIGGER sync_homework_insert AFTER INSERT ON homework BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('homework', new.id);
END;

CREATE TRIGGER sync_homework_update AFTER UPDATE ON homework BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('homework', new.id);
END;

CREATE TRIGGER sync_homework_delete AFTER DELETE ON homework BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('homework', old.id);
END;

CREATE TRIGGER sync_homework_targets_insert AFTER INSERT ON homework_targets BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('homework', new.homework_id);
END;

CREATE TRIGGER sync_homework_targets_delete AFTER DELETE ON homework_targets BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('homework', old.homework_id);
END;

CREATE TRIGGER sync_homework_attachments_insert AFTER INSERT ON homework_attachments BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('homework', new.homework_id);
END;

CREATE TRIGGER sync_homework_attachments_delete AFTER DELETE ON homework_attachments BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('homework', old.homework_id);
END;

CREATE TRIGGER sync_homework_done_insert AFTER INSERT ON homework_done BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('homework', new.homework_id);
END;

CREATE TRIGGER sync_homework_done_delete AFTER DELETE ON homework_done BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('homework', old.homework_id);
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

CREATE TRIGGER sync_folders_insert AFTER INSERT ON folders BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('folder', new.id);
END;

CREATE TRIGGER sync_folders_update AFTER UPDATE ON folders BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('folder', new.id);
END;

CREATE TRIGGER sync_folders_delete AFTER DELETE ON folders BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('folder', old.id);
END;

CREATE TRIGGER sync_comments_insert AFTER INSERT ON comments BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('comments:' || new.target_type, new.target_id);
  INSERT INTO changes (kind, ref_id) VALUES (CASE new.target_type WHEN 'post' THEN 'post' WHEN 'homework' THEN 'homework' ELSE 'material' END, new.target_id);
END;

CREATE TRIGGER sync_comments_update AFTER UPDATE ON comments BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('comments:' || new.target_type, new.target_id);
  INSERT INTO changes (kind, ref_id) VALUES (CASE new.target_type WHEN 'post' THEN 'post' WHEN 'homework' THEN 'homework' ELSE 'material' END, new.target_id);
END;

CREATE TRIGGER sync_comments_delete AFTER DELETE ON comments BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('comments:' || old.target_type, old.target_id);
  INSERT INTO changes (kind, ref_id) VALUES (CASE old.target_type WHEN 'post' THEN 'post' WHEN 'homework' THEN 'homework' ELSE 'material' END, old.target_id);
END;

CREATE TRIGGER sync_subject_groups_insert AFTER INSERT ON subject_groups BEGIN
  INSERT INTO changes (kind, ref_id) SELECT 'material', id FROM materials WHERE subject_id = new.subject_id;
  INSERT INTO changes (kind, ref_id) SELECT 'folder', id FROM folders WHERE subject_id = new.subject_id;
END;

CREATE TRIGGER sync_subject_groups_delete AFTER DELETE ON subject_groups BEGIN
  INSERT INTO changes (kind, ref_id) SELECT 'material', id FROM materials WHERE subject_id = old.subject_id;
  INSERT INTO changes (kind, ref_id) SELECT 'folder', id FROM folders WHERE subject_id = old.subject_id;
END;

CREATE TRIGGER sync_subjects_update AFTER UPDATE OF name, color, avatar ON subjects BEGIN
  INSERT INTO changes (kind, ref_id) SELECT 'homework', id FROM homework WHERE subject_id = new.id;
  INSERT INTO changes (kind, ref_id) SELECT 'post', id FROM posts WHERE subject_id = new.id;
  INSERT INTO changes (kind, ref_id) SELECT 'material', id FROM materials WHERE subject_id = new.id;
END;

CREATE TRIGGER sync_memberships_insert AFTER INSERT ON memberships BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('reset', new.user_id);
END;

CREATE TRIGGER sync_memberships_update AFTER UPDATE ON memberships BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('reset', new.user_id);
END;

CREATE TRIGGER sync_memberships_delete AFTER DELETE ON memberships BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('reset', old.user_id);
END;
