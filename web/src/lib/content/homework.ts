import { put } from '$lib/api';
import { startOfDay } from '$lib/format';
import { toastError } from '$lib/toasts.svelte';
import type { Homework } from '$lib/types';

/** Оптимистичная отметка «выполнено»: меняем сразу, откатываем при ошибке. */
export async function toggleDone(h: Homework, done: boolean) {
	const before = h.done;
	h.done = done;
	try {
		await put(`/api/homework/${h.id}/done`, { value: done });
	} catch (e) {
		h.done = before;
		toastError(e);
	}
}

/** Группировка по дням дедлайна для списка «на неделю». */
export function byDay(items: Homework[]): { day: number; items: Homework[] }[] {
	const map = new Map<number, Homework[]>();
	for (const h of items) {
		const d = startOfDay(h.dueAt);
		if (!map.has(d)) map.set(d, []);
		map.get(d)!.push(h);
	}
	return [...map.entries()].sort((a, b) => a[0] - b[0]).map(([day, items]) => ({ day, items }));
}
