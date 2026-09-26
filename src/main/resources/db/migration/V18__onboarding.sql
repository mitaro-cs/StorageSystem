-- Знакомство с сайтом — окно после регистрации: что это и как пользоваться. Показывается один раз;
-- тем, кто уже зарегистрирован, — не показывается.

ALTER TABLE users ADD COLUMN onboarded_at INTEGER;

UPDATE users SET onboarded_at = created_at;
