-- Кнопка «Сессия» в меню: нужна пару раз в год, поэтому по умолчанию показывается только около
-- сессии (auto — за три недели до начала и до конца, по датам). show — всегда, hide — никогда.
ALTER TABLE study_groups ADD COLUMN session_nav TEXT NOT NULL DEFAULT 'auto'
  CHECK (session_nav IN ('auto', 'show', 'hide'));
