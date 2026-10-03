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

/** Окно между парами: с конца одной до начала следующей. */
export interface Gap {
	from: number;
	to: number;
}

/** Сводка дня для карточки дня на «Расписании»: сколько пар и часов, начало, конец, окна. */
export interface DayStats {
	count: number;
	/** Минут на парах. */
	minutes: number;
	first: Lesson | null;
	last: Lesson | null;
	/** Перерывы от 30 минут — «окна». */
	gaps: Gap[];
	kinds: Partial<Record<LessonKind, number>>;
}

export function dayStats(lessons: Lesson[]): DayStats {
	const list = [...lessons].sort((a, b) => a.startsAt - b.startsAt);
	const kinds: Partial<Record<LessonKind, number>> = {};
	let minutes = 0;
	const gaps: Gap[] = [];
	let end = -Infinity;
	for (const l of list) {
		kinds[l.kind] = (kinds[l.kind] ?? 0) + 1;
		minutes += Math.round((l.endsAt - l.startsAt) / 60_000);
		if (end > -Infinity && l.startsAt - end >= 30 * 60_000)
			gaps.push({ from: end, to: l.startsAt });
		end = Math.max(end, l.endsAt);
	}
	const last = list.reduce<Lesson | null>((a, l) => (!a || l.endsAt > a.endsAt ? l : a), null);
	return { count: list.length, minutes, first: list[0] ?? null, last, gaps, kinds };
}

/** «1 ч 30 мин», «45 мин», «6 ч». */
export function durationText(minutes: number): string {
	const h = Math.floor(minutes / 60);
	const m = minutes % 60;
	if (!h) return `${m} мин`;
	return m ? `${h} ч ${m} мин` : `${h} ч`;
}

/** «Иванов И. И.» из «Иванов Иван Иванович»; уже короткое — как есть. */
export function shortName(full: string): string {
	const p = full.trim().split(/\s+/);
	if (p.length < 2 || p.slice(1).every((x) => /^[А-ЯЁA-Z]\.?$/u.test(x))) return full.trim();
	return `${p[0]} ${p
		.slice(1, 3)
		.map((x) => x[0].toUpperCase() + '.')
		.join(' ')}`;
}

/** Цвет вида пары — полоска на карточке и точки в календаре (как на сайте вуза). */
export const KIND_COLORS: Record<LessonKind, string> = {
	lecture: '#1fa37a',
	practice: '#4f7df5',
	seminar: '#a35cf0',
	lab: '#e0633a',
	consult: '#1f9bb8',
	credit: '#d9a21b',
	exam: '#d9487e',
	other: '#8a8f98'
};

/**
 * Номер учебной недели и чётность: осенью счёт с недели 1 сентября, весной — с недели 1 февраля
 * (первая неделя — нечётная).
 */
export function studyWeek(ms: number): { n: number; odd: boolean } {
	const d = new Date(ms);
	const y = d.getFullYear();
	const m = d.getMonth();
	const start = m >= 7 ? new Date(y, 8, 1) : m === 0 ? new Date(y - 1, 8, 1) : new Date(y, 1, 1);
	const n = Math.round((weekStart(ms) - weekStart(start.getTime())) / (7 * 86_400_000)) + 1;
	return { n: Math.max(1, n), odd: Math.max(1, n) % 2 === 1 };
}

/** Поиск по паре: предмет, название, преподаватель, аудитория, тема. */
export function lessonMatches(l: Lesson, query: string): boolean {
	const q = query.trim().toLowerCase();
	if (!q) return true;
	return [l.subject?.name, l.title, l.teacher, l.place, l.note].some((x) =>
		x?.toLowerCase().includes(q)
	);
}

/** Пара: не было (отменили), прошла, идёт, скоро или позже. */
export function lessonStatus(l: Lesson, now: number): LessonState | 'cancelled' {
	return l.cancelled ? 'cancelled' : lessonState(l, now);
}

/** Сетка месяца: понедельники недель, в которые попадает месяц. */
export function monthWeeks(ms: number): number[] {
	const d = new Date(ms);
	const first = new Date(d.getFullYear(), d.getMonth(), 1).getTime();
	const last = new Date(d.getFullYear(), d.getMonth() + 1, 0).getTime();
	const out: number[] = [];
	for (let w = weekStart(first); w <= last; w = addDays(w, 7)) out.push(w);
	return out;
}
