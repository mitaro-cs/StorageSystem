-- Фон карточки предмета: широкая картинка (16:9) вместо иконки. Файлы — в avatars/ (<id>-1280 и
-- <id>-480.webp), зашифрованные, как аватары.

ALTER TABLE subjects ADD COLUMN cover TEXT;
