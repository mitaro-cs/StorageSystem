-- «Не мой предмет»: человек убирает у себя предмет другой подгруппы (английский №1 и №2, лабораторные
-- по подгруппам). Его задания, новости и материалы не показываются в общих лентах и не приходят
-- уведомлениями; на странице самого предмета всё видно, вернуть можно в любой момент.
CREATE TABLE subject_hidden (
  user_id    INTEGER NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  subject_id INTEGER NOT NULL REFERENCES subjects (id) ON DELETE CASCADE,
  hidden_at  INTEGER NOT NULL,
  PRIMARY KEY (user_id, subject_id)
) STRICT, WITHOUT ROWID;

CREATE INDEX subject_hidden_subject ON subject_hidden (subject_id);
