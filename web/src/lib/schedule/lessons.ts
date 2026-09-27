import { fmtTime, plural, startOfDay } from '$lib/format';
import type { Lesson, LessonKind } from '$lib/types';

/**
 * Пары расписания: виды, время, «идёт сейчас». Страница «Расписание», «Сегодня», вкладка «Пары»
 * предмета и страница пары.
 */

export const LESSON_KINDS: {
	value: LessonKind;
	label: string;
	short: string;
	/** «1 лекция, 2 лекции, 5 лекций». */
	forms: [string, string, string];
}[] = [
	{ value: 'lecture', label: 'Лекция', short: 'Лекция', forms: ['лекция', 'лекции', 'лекций'] },
	{
		value: 'practice',
		label: 'Практика',
		short: 'Практика',
		forms: ['практика', 'практики', 'практик']
	},
	{
		value: 'seminar',
		label: 'Семинар',
		short: 'Семинар',
		forms: ['семинар', 'семинара', 'семинаров']
	},
	{
		value: 'lab',
		label: 'Лабораторная',
		short: 'Лаба',
		forms: ['лабораторная', 'лабораторные', 'лабораторных']
	},
	{
		value: 'consult',
		label: 'Консультация',
		short: 'Консультация',
		forms: ['консультация', 'консультации', 'консультаций']
	},
	{ value: 'credit', label: 'Зачёт', short: 'Зачёт', forms: ['зачёт', 'зачёта', 'зачётов'] },
	{
		value: 'exam',
		label: 'Экзамен',
		short: 'Экзамен',
		forms: ['экзамен', 'экзамена', 'экзаменов']
	},
	{ value: 'other', label: 'Занятие', short: '', forms: ['занятие', 'занятия', 'занятий'] }
];

export function lessonKind(k: LessonKind | string | null | undefined) {
	return LESSON_KINDS.find((x) => x.value === k) ?? LESSON_KINDS[LESSON_KINDS.length - 1];
}

/** Как называть пару: предмет, а если его нет — название из расписания. */
export const lessonName = (l: Pick<Lesson, 'subject' | 'title'>) => l.subject?.name ?? l.title;

/** «9:30–11:05». */
export const lessonTime = (l: Pick<Lesson, 'startsAt' | 'endsAt'>) =>
	`${fmtTime(l.startsAt)}–${fmtTime(l.endsAt)}`;

export type LessonState = 'past' | 'now' | 'soon' | 'later';

/** Прошла, идёт, скоро (в ближайший час) или позже. */
export function lessonState(l: Pick<Lesson, 'startsAt' | 'endsAt'>, now: number): LessonState {
	if (now >= l.endsAt) return 'past';
	if (now >= l.startsAt) return 'now';
	return l.startsAt - now <= 60 * 60 * 1000 ? 'soon' : 'later';
}

/** Сколько прошло от пары, 0–1 — полоска у идущей пары. */
export function lessonProgress(l: Pick<Lesson, 'startsAt' | 'endsAt'>, now: number): number {
	return Math.min(1, Math.max(0, (now - l.startsAt) / Math.max(1, l.endsAt - l.startsAt)));
}

/** «через 25 мин», «через 2 ч 10 мин». */
export function untilText(ms: number): string {
	const m = Math.max(1, Math.round(ms / 60_000));
	if (m < 60) return `через ${m} мин`;
	const h = Math.floor(m / 60);
	const rest = m % 60;
	return rest ? `через ${h} ч ${rest} мин` : `через ${h} ч`;
}

/** Понедельник недели, в которую попадает момент (полночь по местному времени). */
export function weekStart(ms: number): number {
	const d = new Date(startOfDay(ms));
	const shift = (d.getDay() + 6) % 7;
	d.setDate(d.getDate() - shift);
	return d.getTime();
}

/** День недели со сдвигом: addDays(понедельник, 3) — четверг (с учётом перевода часов). */
export function addDays(ms: number, days: number): number {
	const d = new Date(ms);
	d.setDate(d.getDate() + days);
	return d.getTime();
}

/** Пары по дням: [полночь дня, пары по времени]. */
export function byDay(lessons: Lesson[]): Map<number, Lesson[]> {
	const out = new Map<number, Lesson[]>();
	for (const l of [...lessons].sort((a, b) => a.startsAt - b.startsAt || a.id - b.id)) {
		const day = startOfDay(l.startsAt);
		const list = out.get(day);
		if (list) list.push(l);
		else out.set(day, [l]);
	}
	return out;
}

/** «4 лекции, 12 практик» — сводка по видам. */
export function kindsText(kinds: Partial<Record<LessonKind, number>>): string {
	return LESSON_KINDS.filter((k) => kinds[k.value])
		.map((k) => `${kinds[k.value]} ${plural(kinds[k.value]!, k.forms)}`)
		.join(', ');
}
