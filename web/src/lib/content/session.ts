import type { Homework, MeGroup } from '$lib/types';

/**
 * Режим «Сессия»: даты задаёт староста (MeGroup.session), зачёты и экзамены — обычные задания
 * с kind = credit | exam. Здесь только расчёты — без обращений к серверу.
 */

const DAY = 24 * 60 * 60 * 1000;
/** Зачётная неделя бывает до официальной сессии: берём зачёты и экзамены за три недели до начала. */
export const LEAD_DAYS = 21;

export interface SessionDates {
	from: number;
	to: number;
}

export type Phase =
	| { kind: 'none' }
	| { kind: 'before'; days: number }
	| { kind: 'during'; day: number; total: number }
	| { kind: 'after' };

function midnight(ms: number): number {
	const d = new Date(ms);
	d.setHours(0, 0, 0, 0);
	return d.getTime();
}

/** Календарных дней до дня ts: 0 — сегодня, 1 — завтра, −1 — вчера. */
export function daysUntil(ts: number, now: number): number {
	return Math.round((midnight(ts) - midnight(now)) / DAY);
}

export function phase(dates: SessionDates | null | undefined, now: number): Phase {
	if (!dates) return { kind: 'none' };
	const toStart = daysUntil(dates.from, now);
	if (toStart > 0) return { kind: 'before', days: toStart };
	const total = daysUntil(dates.to, dates.from) + 1;
	const day = 1 - toStart;
	return day <= total ? { kind: 'during', day, total } : { kind: 'after' };
}

/** Зачёты и экзамены этой сессии; без дат — все, что пришли с сервера (недавние и будущие). */
export function sessionExams(
	exams: Homework[],
	dates: SessionDates | null | undefined
): Homework[] {
	const list = dates
		? exams.filter((e) => e.dueAt >= dates.from - LEAD_DAYS * DAY && e.dueAt < dates.to + DAY)
		: exams;
	return [...list].sort((a, b) => a.dueAt - b.dueAt);
}

/** Ближайший несданный: сегодняшний экзамен ещё «впереди», даже если начался утром. */
export function nextExam(list: Homework[], now: number): Homework | null {
	const today = midnight(now);
	return list.find((e) => !e.done && e.dueAt >= today) ?? null;
}

export function progress(list: Homework[]): { done: number; total: number } {
	return { done: list.filter((e) => e.done).length, total: list.length };
}

/**
 * Карточка на главной: за три недели до начала и до конца сессии; без дат — если зачёт или
 * экзамен в ближайшие две недели.
 */
export function sessionVisible(
	dates: SessionDates | null | undefined,
	exams: Homework[],
	now: number
): boolean {
	const p = phase(dates, now);
	if (p.kind === 'before') return p.days <= LEAD_DAYS;
	if (p.kind === 'during') return true;
	if (p.kind === 'after')
		return exams.some(
			(e) => !e.done && daysUntil(e.dueAt, now) >= 0 && daysUntil(e.dueAt, now) <= 14
		);
	return exams.some((e) => daysUntil(e.dueAt, now) >= 0 && daysUntil(e.dueAt, now) <= 14);
}

/**
 * Кнопка «Сессия» в меню. Её выбирает староста в «Настройки → Семестр»: всегда, никогда или около
 * сессии — за три недели до начала и до последнего дня (по датам; без дат — не видна).
 */
export function sessionNavVisible(
	group: Pick<MeGroup, 'session' | 'sessionNav'>,
	now: number
): boolean {
	const mode = group.sessionNav ?? 'auto';
	if (mode === 'show') return true;
	if (mode === 'hide') return false;
	const p = phase(group.session, now);
	return p.kind === 'during' || (p.kind === 'before' && p.days <= LEAD_DAYS);
}

/** Даты сессии выбранной группы; в режиме «все группы» — если они есть ровно у одной. */
export function datesFor(groups: MeGroup[], groupId: number | null): SessionDates | null {
	if (groupId !== null) return groups.find((g) => g.id === groupId)?.session ?? null;
	const withDates = groups.filter((g) => g.session);
	return withDates.length === 1 ? withDates[0].session : null;
}

/** Полдень выбранного дня: сервер приведёт его к полуночи своего часового пояса без сдвига даты. */
export function dayToMs(value: string): number {
	const [y, m, d] = value.split('-').map(Number);
	return new Date(y, m - 1, d, 12).getTime();
}

export function msToDay(ms: number): string {
	const d = new Date(ms);
	const p = (n: number) => String(n).padStart(2, '0');
	return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}
