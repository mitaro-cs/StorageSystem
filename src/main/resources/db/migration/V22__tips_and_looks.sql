-- Подсказки при первом заходе в раздел (0.6): какие уже закрыты, через запятую. Тем, кто уже
-- пользуется сайтом, не показываются — «*» значит «все прочитаны».

ALTER TABLE users ADD COLUMN tips_seen TEXT NOT NULL DEFAULT '';

UPDATE users SET tips_seen = '*' WHERE onboarded_at IS NOT NULL;

-- Оформление общее для всех устройств человека (0.6): тема, дизайн, цвет, приглушение и размытие
-- фона, значок — JSON (users.appearance, пусто — ещё не выбирал), своя картинка фона — WebP в
-- каталоге аватаров (users.background — её идентификатор).
ALTER TABLE users ADD COLUMN appearance TEXT NOT NULL DEFAULT '';
ALTER TABLE users ADD COLUMN background TEXT;
