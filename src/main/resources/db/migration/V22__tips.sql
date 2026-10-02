-- Подсказки при первом заходе в раздел (0.6): какие уже закрыты, через запятую. Тем, кто уже
-- пользуется сайтом, не показываются — «*» значит «все прочитаны».

ALTER TABLE users ADD COLUMN tips_seen TEXT NOT NULL DEFAULT '';

UPDATE users SET tips_seen = '*' WHERE onboarded_at IS NOT NULL;
