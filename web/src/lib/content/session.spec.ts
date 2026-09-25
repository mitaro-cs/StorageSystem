import { describe, expect, it } from 'vitest';
import type { Homework, MeGroup } from '$lib/types';
import {
	datesFor,
	dayToMs,
	daysUntil,
	msToDay,
	nextExam,
	phase,
	progress,
	sessionExams,
	sessionVisible,
	sessionNavVisible
} from './session';

const at = (y: number, m: number, d: number, h = 0) => new Date(y, m - 1, d, h).getTime();
const exam = (id: number, dueAt: number, done = false, kind: 'exam' | 'credit' = 'exam') =>
	({ id, dueAt, done, kind }) as Homework;

describe('сессия', () => {
	const dates = { from: at(2027, 1, 10), to: at(2027, 1, 26) };

	it('считает дни по календарю, а не по часам', () => {
		expect(daysUntil(at(2027, 1, 11, 1), at(2027, 1, 10, 23))).toBe(1);
		expect(daysUntil(at(2027, 1, 10, 9), at(2027, 1, 10, 20))).toBe(0);
		expect(daysUntil(at(2027, 1, 9), at(2027, 1, 10))).toBe(-1);
	});

	it('фаза: до, во время (день из дней), после', () => {
		expect(phase(null, at(2027, 1, 1))).toEqual({ kind: 'none' });
		expect(phase(dates, at(2027, 1, 1))).toEqual({ kind: 'before', days: 9 });
		expect(phase(dates, at(2027, 1, 10, 8))).toEqual({ kind: 'during', day: 1, total: 17 });
		expect(phase(dates, at(2027, 1, 26, 22))).toEqual({ kind: 'during', day: 17, total: 17 });
		expect(phase(dates, at(2027, 1, 27))).toEqual({ kind: 'after' });
	});

	it('в сессию попадают и зачёты зачётной недели, но не прошлогодние', () => {
		const list = [
			exam(1, at(2027, 1, 20, 9)),
			exam(2, at(2026, 12, 25, 10), false, 'credit'),
			exam(3, at(2026, 6, 20, 9)),
			exam(4, at(2027, 1, 27, 9))
		];
		expect(sessionExams(list, dates).map((e) => e.id)).toEqual([2, 1]);
		expect(sessionExams(list, null).map((e) => e.id)).toEqual([3, 2, 1, 4]);
	});

	it('ближайший — несданный, сегодняшний тоже считается', () => {
		const now = at(2027, 1, 20, 11);
		const list = [
			exam(1, at(2027, 1, 15), true),
			exam(2, at(2027, 1, 20, 9)),
			exam(3, at(2027, 1, 24))
		];
		expect(nextExam(list, now)?.id).toBe(2);
		expect(progress(list)).toEqual({ done: 1, total: 3 });
		expect(nextExam([exam(1, at(2027, 1, 19), false)], now)).toBeNull();
	});

	it('карточка на главной: за три недели до начала и во время', () => {
		expect(sessionVisible(dates, [], at(2026, 12, 1))).toBe(false);
		expect(sessionVisible(dates, [], at(2026, 12, 22))).toBe(true);
		expect(sessionVisible(dates, [], at(2027, 1, 15))).toBe(true);
		expect(sessionVisible(dates, [], at(2027, 2, 1))).toBe(false);
		// Без дат — только если экзамен в ближайшие две недели.
		expect(sessionVisible(null, [exam(1, at(2027, 1, 20))], at(2027, 1, 10))).toBe(true);
		expect(sessionVisible(null, [exam(1, at(2027, 2, 20))], at(2027, 1, 10))).toBe(false);
	});

	it('даты группы; в «всех группах» — только если они одни', () => {
		const g = (id: number, session: MeGroup['session']) => ({ id, session }) as MeGroup;
		expect(datesFor([g(1, dates), g(2, null)], 2)).toBeNull();
		expect(datesFor([g(1, dates), g(2, null)], null)).toEqual(dates);
		expect(datesFor([g(1, dates), g(2, dates)], null)).toBeNull();
	});

	it('день из поля даты уходит полднем и возвращается тем же днём', () => {
		expect(new Date(dayToMs('2027-01-10')).getHours()).toBe(12);
		expect(msToDay(dayToMs('2027-01-10'))).toBe('2027-01-10');
		expect(msToDay(at(2027, 1, 10))).toBe('2027-01-10');
	});
});

describe('кнопка «Сессия» в меню', () => {
	const DAY = 24 * 60 * 60 * 1000;
	const now = new Date(2026, 8, 26, 12).getTime();
	const at = (days: number) => now + days * DAY;
	const group = (sessionNav: MeGroup['sessionNav'], from?: number, to?: number) => ({
		sessionNav,
		session: from !== undefined && to !== undefined ? { from, to } : null
	});

	it('староста выбрал «всегда» или «не показывать» — так и есть', () => {
		expect(sessionNavVisible(group('show'), now)).toBe(true);
		expect(sessionNavVisible(group('hide', at(-1), at(10)), now)).toBe(false);
	});

	it('«около сессии» — за три недели до начала и до последнего дня', () => {
		expect(sessionNavVisible(group('auto', at(30), at(50)), now)).toBe(false);
		expect(sessionNavVisible(group('auto', at(21), at(40)), now)).toBe(true);
		expect(sessionNavVisible(group('auto', at(-3), at(10)), now)).toBe(true);
		expect(sessionNavVisible(group('auto', at(-30), at(-2)), now)).toBe(false);
	});

	it('без дат и у данных от прежних версий (поля нет) — не видна', () => {
		expect(sessionNavVisible(group('auto'), now)).toBe(false);
		expect(sessionNavVisible(group(undefined, at(5), at(20)), now)).toBe(true);
		expect(sessionNavVisible(group(undefined), now)).toBe(false);
	});
});
