-- Тип задания: домашнее, лабораторная, контрольная, зачёт, экзамен.
-- Место — аудитория или ссылка на встречу: нужно зачёту и экзамену.
ALTER TABLE homework ADD COLUMN kind TEXT NOT NULL DEFAULT 'homework'
  CHECK (kind IN ('homework', 'lab', 'test', 'credit', 'exam'));
ALTER TABLE homework ADD COLUMN place TEXT NOT NULL DEFAULT '';

CREATE INDEX homework_kind ON homework (kind, due_at);

-- Сессия группы: первый и последний день (полночь по часовому поясу сайта, мс). NULL — не задана.
ALTER TABLE study_groups ADD COLUMN session_from INTEGER;
ALTER TABLE study_groups ADD COLUMN session_to INTEGER;
