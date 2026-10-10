import type { LessonKind } from '$lib/types';

/**
 * Названия видов пар – отдельным крошечным модулем (1.0.2): странице задания нужна одна подпись
 * «Практика · …», а весь lessons.ts утяжелял её до границы бюджета. Те же подписи – в lessons.ts
 * (`LESSON_KINDS`): импорт отсюда выносил модуль в отдельный файл расписания.
 */
export const LESSON_LABELS: Record<LessonKind, string> = {
	lecture: 'Лекция',
	practice: 'Практика',
	seminar: 'Семинар',
	lab: 'Лабораторная',
	consult: 'Консультация',
	credit: 'Зачёт',
	exam: 'Экзамен',
	retake: 'Пересдача',
	other: 'Занятие'
};
