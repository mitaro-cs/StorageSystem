-- Приложение хоста для компьютера.
-- Локальная сессия: окно приложения на компьютере хоста. Действует только для запросов с этого же
-- компьютера (не через туннель), поэтому не требует 2FA.
ALTER TABLE sessions ADD COLUMN local INTEGER NOT NULL DEFAULT 0;

-- Режим управления: 0 — скрыть кнопки администратора и старосты, пользоваться как участник.
ALTER TABLE users ADD COLUMN manage_mode INTEGER NOT NULL DEFAULT 1;
