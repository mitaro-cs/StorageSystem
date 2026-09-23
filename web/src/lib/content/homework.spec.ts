import { describe, expect, it } from 'vitest';
import { byDay } from './homework';
import type { Homework } from '$lib/types';

const hw = (id: number, due: Date) => ({ id, dueAt: due.getTime() }) as Homework;

describe('byDay', () => {
	it('группирует по календарному дню и сортирует', () => {
		const groups = byDay([
			hw(1, new Date(2026, 8, 25, 10)),
			hw(2, new Date(2026, 8, 24, 23)),
			hw(3, new Date(2026, 8, 25, 18))
		]);
		expect(groups.map((g) => g.items.map((h) => h.id))).toEqual([[2], [1, 3]]);
	});
});
