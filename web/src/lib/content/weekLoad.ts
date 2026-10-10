import { plural } from '$lib/format';
import type { HomeworkKind } from './kinds';
import type { Homework } from '$lib/types';

/**
 * Нагрузка недели (1.0.2): сколько «весит» каждый из семи дней начиная с сегодня. Лабораторная и
 * контрольная тяжелее домашнего, зачёт и экзамен – ещё тяжелее; сделанное не считается.
 */
export const WEIGHT: Record<HomeworkKind, number> = {
	homework: 1,
	lab: 2,
	test: 2,
	credit: 3,
	exam: 3
};

export interface LoadDay {
	day: number;
	/** Несделанные задания дня. */
	open: Homework[];
	done: number;
	weight: number;
	/** 0 – свободно, 4 – завал. */
	level: 0 | 1 | 2 | 3 | 4;
}

export function level(weight: number): LoadDay['level'] {
	if (weight <= 0) return 0;
	if (weight === 1) return 1;
	if (weight <= 3) return 2;
	if (weight <= 5) return 3;
	return 4;
}

/** Семь дней с сегодняшнего; полночь – по местному времени (переход на летнее время не сдвигает дни). */
export function weekLoad(items: Homework[], now: number, days = 7): LoadDay[] {
	const base = new Date(now);
	const out: LoadDay[] = [];
	for (let i = 0; i < days; i++) {
		const from = new Date(base.getFullYear(), base.getMonth(), base.getDate() + i).getTime();
		const to = new Date(base.getFullYear(), base.getMonth(), base.getDate() + i + 1).getTime();
		const all = items.filter((h) => h.dueAt >= from && h.dueAt < to);
		const open = all.filter((h) => !h.done);
		const weight = open.reduce((s, h) => s + (WEIGHT[h.kind] ?? 1), 0);
		out.push({ day: from, open, done: all.length - open.length, weight, level: level(weight) });
	}
	return out;
}

const FORMS: Record<HomeworkKind, [string, string, string]> = {
	homework: ['домашнее', 'домашних', 'домашних'],
	lab: ['лабораторная', 'лабораторные', 'лабораторных'],
	test: ['контрольная', 'контрольные', 'контрольных'],
	credit: ['зачёт', 'зачёта', 'зачётов'],
	exam: ['экзамен', 'экзамена', 'экзаменов']
};
const ORDER: HomeworkKind[] = ['exam', 'credit', 'lab', 'test', 'homework'];

/** «2 лабораторные и контрольная» – что в этот день, тяжёлое первым. */
export function describe(open: Homework[]): string {
	const parts = ORDER.flatMap((k) => {
		const n = open.filter((h) => (h.kind ?? 'homework') === k).length;
		if (!n) return [];
		const word = plural(n, FORMS[k]);
		return [n === 1 ? word : `${n} ${word}`];
	});
	if (parts.length <= 1) return parts[0] ?? '';
	return `${parts.slice(0, -1).join(', ')} и ${parts[parts.length - 1]}`;
}

/** Самый тяжёлый день (раньше – при равенстве); нет заданий – null. */
export function peak(days: LoadDay[]): LoadDay | null {
	let best: LoadDay | null = null;
	for (const d of days) if (d.weight > 0 && (!best || d.weight > best.weight)) best = d;
	return best;
}
