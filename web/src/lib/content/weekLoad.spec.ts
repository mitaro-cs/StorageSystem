import { describe as group, expect, it } from 'vitest';
import { describe, level, peak, weekLoad } from './weekLoad';
import type { Homework } from '$lib/types';

const hw = (id: number, due: Date, kind: Homework['kind'] = 'homework', done = false) =>
	({ id, dueAt: due.getTime(), kind, done }) as Homework;

const now = new Date(2026, 9, 12, 9).getTime(); // пн, 12 октября

group('weekLoad', () => {
	it('семь дней с сегодняшнего, сделанное не весит, тяжёлое тяжелее', () => {
		const days = weekLoad(
			[
				hw(1, new Date(2026, 9, 12, 23, 59)),
				hw(2, new Date(2026, 9, 14, 10), 'lab'),
				hw(3, new Date(2026, 9, 14, 12), 'test'),
				hw(4, new Date(2026, 9, 14, 18), 'homework', true),
				hw(5, new Date(2026, 9, 19, 0)) // восьмой день – мимо
			],
			now
		);
		expect(days).toHaveLength(7);
		expect(days.map((d) => d.weight)).toEqual([1, 0, 4, 0, 0, 0, 0]);
		expect(days[2].done).toBe(1);
		expect(days[2].level).toBe(3);
		expect(peak(days)?.day).toBe(new Date(2026, 9, 14).getTime());
	});

	it('дни – по местной полуночи и через переход на зимнее время', () => {
		const days = weekLoad([], new Date(2026, 9, 24, 12).getTime());
		expect(days.map((d) => new Date(d.day).getDate())).toEqual([24, 25, 26, 27, 28, 29, 30]);
		expect(days.every((d) => new Date(d.day).getHours() === 0)).toBe(true);
	});

	it('пустая неделя – без пика', () => {
		expect(peak(weekLoad([], now))).toBeNull();
	});
});

group('level и describe', () => {
	it('уровни по весу', () => {
		expect([0, 1, 2, 3, 4, 5, 6, 9].map(level)).toEqual([0, 1, 2, 2, 3, 3, 4, 4]);
	});

	it('что в этот день – тяжёлое первым', () => {
		const d = new Date(2026, 9, 14);
		expect(describe([hw(1, d, 'lab'), hw(2, d, 'lab'), hw(3, d, 'test')])).toBe(
			'2 лабораторные и контрольная'
		);
		expect(describe([hw(1, d), hw(2, d, 'exam'), hw(3, d)])).toBe('экзамен и 2 домашних');
		expect(describe([])).toBe('');
	});
});
