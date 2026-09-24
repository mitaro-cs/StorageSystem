-- Иконка предмета из встроенного набора (ключ вроде «sigma» или «atom»); NULL — подобрать по названию.
ALTER TABLE subjects ADD COLUMN icon TEXT;

-- Сложность задания: 1 — легко, 2 — средне, 3 — сложно; NULL — не указана.
ALTER TABLE homework ADD COLUMN difficulty INTEGER CHECK (difficulty BETWEEN 1 AND 3);
