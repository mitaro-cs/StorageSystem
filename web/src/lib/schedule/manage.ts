import { del, put } from '$lib/api';
import { plural } from '$lib/format';
import { toast, toastError } from '$lib/toasts.svelte';
import type { Lesson } from '$lib/types';
import { ask } from '$lib/ui/ask.svelte';

// Действия старосты на «Расписании» – грузятся по нажатию (бюджет страницы).

/** Очистить расписание группы; true – очистили. */
export async function clearSchedule(groupId: number): Promise<boolean> {
	if (
		!(await ask(
			'Все пары группы исчезнут из расписания. Задания и материалы к ним останутся – просто без пары.',
			{ title: 'Очистить расписание?', ok: 'Очистить', danger: true }
		))
	)
		return false;
	try {
		const r = await del<{ deleted: number }>(`/api/groups/${groupId}/schedule`);
		toast(`Удалено ${r.deleted} ${plural(r.deleted, ['пара', 'пары', 'пар'])}`, 'ok');
		return true;
	} catch (e) {
		toastError(e);
		return false;
	}
}

/** «Пары не было» или вернуть; новая пара – или null при ошибке. */
export async function setCancelled(l: Lesson, value: boolean): Promise<Lesson | null> {
	try {
		const updated = await put<Lesson>(`/api/lessons/${l.id}/cancelled`, { value });
		toast(value ? 'Отмечено: пары не было' : 'Пара снова в расписании', 'ok');
		return updated;
	} catch (e) {
		toastError(e);
		return null;
	}
}
