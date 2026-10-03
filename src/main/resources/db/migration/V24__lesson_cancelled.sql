-- «Пары не было» (0.7): староста отмечает отменённую пару. Повторная загрузка файла календаря
-- отметку не сбрасывает.
ALTER TABLE lessons ADD COLUMN cancelled INTEGER NOT NULL DEFAULT 0 CHECK (cancelled IN (0, 1));
