import { describe, expect, it } from 'vitest';
import type { Lesson } from '$lib/types';
import {
	addDays,
	byDay,
	kindsText,
	lessonKind,
	lessonName,
	lessonProgress,
	lessonState,
	untilText,
	weekStart
} from './lessons';

const at = (d: string) => new Date(d).getTime();

function lesson(start: string, end: string, extra: Partial<Lesson> = {}): Lesson {
	return {
		id: 1,
		groupId: 1,
		subject: { id: 7, name: 'Физика', color: '#4f7df5' },
		title: 'Физика (лек.)',
		kind: 'lecture',
		startsAt: at(start),
		endsAt: at(end),
		place: '214',
		teacher: '',
		note: '',
		homework: 0,
		materials: 0,
		can: { edit: false },
		...extra
	};
}

describe('расписание', () => {
	it('название — предмет, без предмета — из расписания', () => {
		expect(lessonName(lesson('2026-09-01T09:30', '2026-09-01T11:05'))).toBe('Физика');
		expect(
			lessonName(
				lesson('2026-09-01T09:30', '2026-09-01T11:05', { subject: null, title: 'Кураторский час' })
			)
		).toBe('Кураторский час');
		expect(lessonKind('lab').label).toBe('Лабораторная');
		expect(lessonKind('что-то').label).toBe('Занятие');
	});

	it('прошла, идёт, скоро, позже — и полоска у идущей', () => {
		const l = lesson('2026-09-01T09:30', '2026-09-01T11:00');
		expect(lessonState(l, at('2026-09-01T08:00'))).toBe('later');
		expect(lessonState(l, at('2026-09-01T09:00'))).toBe('soon');
		expect(lessonState(l, at('2026-09-01T10:15'))).toBe('now');
		expect(lessonProgress(l, at('2026-09-01T10:15'))).toBeCloseTo(0.5);
		expect(lessonState(l, at('2026-09-01T11:00'))).toBe('past');
	});

	it('сводка по видам', () => {
		expect(kindsText({ lecture: 4, practice: 12 })).toBe('4 лекции, 12 практик');
		expect(kindsText({ lab: 1, other: 5 })).toBe('1 лабораторная, 5 занятий');
	});

	it('«через …» по-человечески', () => {
		expect(untilText(25 * 60_000)).toBe('через 25 мин');
		expect(untilText(130 * 60_000)).toBe('через 2 ч 10 мин');
		expect(untilText(120 * 60_000)).toBe('через 2 ч');
	});

	it('неделя с понедельника, пары по дням по порядку', () => {
		// 3 сентября 2026 — четверг, неделя начинается 31 августа.
		expect(weekStart(at('2026-09-03T15:00'))).toBe(at('2026-08-31T00:00'));
		expect(weekStart(at('2026-08-31T00:00'))).toBe(at('2026-08-31T00:00'));
		expect(addDays(at('2026-08-31T00:00'), 6)).toBe(at('2026-09-06T00:00'));
		const days = byDay([
			lesson('2026-09-02T13:00', '2026-09-02T14:30', { id: 3 }),
			lesson('2026-09-01T09:30', '2026-09-01T11:05', { id: 1 }),
			lesson('2026-09-02T09:30', '2026-09-02T11:05', { id: 2 })
		]);
		expect([...days.keys()]).toEqual([at('2026-09-01T00:00'), at('2026-09-02T00:00')]);
		expect(days.get(at('2026-09-02T00:00'))!.map((l) => l.id)).toEqual([2, 3]);
	});
});
