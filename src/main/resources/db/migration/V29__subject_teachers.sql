-- Преподаватели по видам пар (0.9.6): «lecture=Иванов И. И.» по строке на вид. Пусто – у всех пар
-- один преподаватель из subjects.teacher.
ALTER TABLE subjects ADD COLUMN teachers TEXT NOT NULL DEFAULT '';
