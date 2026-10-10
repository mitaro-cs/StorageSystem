-- Реакции на новости (1.0.2): 👍 ❤️ 🔥 😂 😮 ❓ – староста видит, что объявление прочитали, без «+»
-- в комментариях. Человек – по одной реакции каждого вида на новость.
CREATE TABLE post_reactions (
  post_id INTEGER NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
  user_id INTEGER NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  emoji   TEXT    NOT NULL,
  at      INTEGER NOT NULL,
  PRIMARY KEY (post_id, user_id, emoji)
) STRICT;

CREATE INDEX post_reactions_user ON post_reactions (user_id);

-- Копия на устройстве и живые обновления: реакция меняет новость.
CREATE TRIGGER sync_post_reactions_insert AFTER INSERT ON post_reactions BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('post', new.post_id);
END;

CREATE TRIGGER sync_post_reactions_delete AFTER DELETE ON post_reactions BEGIN
  INSERT INTO changes (kind, ref_id) VALUES ('post', old.post_id);
END;
