import { describe, expect, it } from 'vitest';
import type { Lesson } from '$lib/types';
import { abbr } from './month';
import {
	addDays,
	monthWeeks,
	studyWeek,
	byDay,
	dayStats,
	durationText,
	shortName,
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

	it('сводка дня: пары, часы, начало, конец и окна от 30 минут', () => {
		const s = dayStats([
			lesson('2026-09-28T13:00', '2026-09-28T14:30', { id: 3, kind: 'practice' }),
			lesson('2026-09-28T09:30', '2026-09-28T11:00', { id: 1 }),
			lesson('2026-09-28T11:15', '2026-09-28T12:45', { id: 2 })
		]);
		expect(s.count).toBe(3);
		expect(durationText(s.minutes)).toBe('4 ч 30 мин');
		expect(s.first?.id).toBe(1);
		expect(s.last?.id).toBe(3);
		// 11:00–11:15 — перемена, не окно; 12:45–13:00 — тоже.
		expect(s.gaps).toEqual([]);
		const w = dayStats([
			lesson('2026-09-28T09:30', '2026-09-28T11:00'),
			lesson('2026-09-28T15:10', '2026-09-28T16:40', { id: 2 })
		]);
		expect(w.gaps).toEqual([{ from: at('2026-09-28T11:00'), to: at('2026-09-28T15:10') }]);
		expect(dayStats([]).first).toBeNull();
	});

	it('преподаватель — коротко: фамилия и инициалы', () => {
		expect(shortName('Иванов Иван Иванович')).toBe('Иванов И. И.');
		expect(shortName('Петров А. В.')).toBe('Петров А. В.');
		expect(shortName('Smith')).toBe('Smith');
		expect(durationText(45)).toBe('45 мин');
		expect(durationText(120)).toBe('2 ч');
	});
});

describe('месяц и неделя', () => {
	it('чётность от 1 сентября и 1 февраля', () => {
		expect(studyWeek(new Date(2026, 8, 1).getTime())).toEqual({ n: 1, odd: true });
		expect(studyWeek(new Date(2026, 8, 9).getTime())).toEqual({ n: 2, odd: false });
		expect(studyWeek(new Date(2026, 9, 5).getTime()).n).toBe(6);
		expect(studyWeek(new Date(2027, 0, 11).getTime()).n).toBeGreaterThan(18);
		expect(studyWeek(new Date(2027, 1, 3).getTime())).toEqual({ n: 1, odd: true });
	});
	it('сокращения названий', () => {
		expect(abbr('Высшая математика')).toBe('ВМ');
		expect(abbr('Теория вероятностей и математическая статистика')).toBe('ТВМС');
		expect(abbr('Физика')).toBe('Физ');
		expect(abbr('Английский язык №2')).toBe('АЯ');
	});
	it('недели месяца', () => {
		const w = monthWeeks(new Date(2026, 9, 15).getTime());
		expect(w).toHaveLength(5);
		expect(new Date(w[0]).getDate()).toBe(28);
	});
});
