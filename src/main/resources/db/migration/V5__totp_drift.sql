-- Сдвиг часов устройства с аутентификатором (в шагах TOTP по 30 с), RFC 6238, раздел 6.
-- Запоминается при подключении 2FA и уточняется при каждом входе.

ALTER TABLE users ADD COLUMN totp_drift INTEGER NOT NULL DEFAULT 0;
