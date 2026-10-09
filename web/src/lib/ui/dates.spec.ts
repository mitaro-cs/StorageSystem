import { describe, expect, it } from 'vitest';
import { maskDay, maskTime, monthGrid, parseDay, parseTime, showDay } from './dates';

describe('поля дат', () => {
	it('показывает и разбирает день', () => {
		expect(showDay('2026-10-12')).toBe('12.10.2026');
		expect(parseDay('12.10.2026')).toBe('2026-10-12');
		expect(parseDay('1.2.2026')).toBe('2026-02-01');
		expect(parseDay('2026-10-12')).toBe('2026-10-12');
		expect(parseDay('31.02.2026')).toBeNull();
		expect(parseDay('12.10')).toBeNull();
	});
	it('ставит точки и двоеточие по мере ввода', () => {
		expect(maskDay('1210')).toBe('12.10');
		expect(maskDay('12102026')).toBe('12.10.2026');
		expect(maskTime('093')).toBe('09:3');
		expect(maskTime('0930')).toBe('09:30');
	});
	it('разбирает время', () => {
		expect(parseTime('9:30')).toBe('09:30');
		expect(parseTime('0930')).toBe('09:30');
		expect(parseTime('24:00')).toBeNull();
	});
	it('месяц с понедельника', () => {
		// Октябрь 2026 начинается в четверг.
		const g = monthGrid(2026, 9);
		expect(g[0]).toEqual([null, null, null, 1, 2, 3, 4]);
		expect(g.flat().filter(Boolean)).toHaveLength(31);
	});
});
