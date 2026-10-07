import { goto } from '$app/navigation';
import { del } from '$lib/api';
import { loadSubjects } from '$lib/data.svelte';
import { toast, toastError } from '$lib/toasts.svelte';
import type { Subject } from '$lib/types';
import { ask } from '$lib/ui/ask.svelte';

/**
 * Редкие действия старосты с предметом (0.7) – из меню «…» страницы предмета, код грузится по
 * нажатию: удалить насовсем (разделить на подгруппы – окно SubgroupSplit).
 */

/** Удалить совсем: задания, материалы и их файлы – тоже. Пары и новости останутся без предмета. */
export async function deleteSubject(s: Subject) {
	const ok = await ask(
		`Удалится и всё внутри: задания, материалы, файлы и комментарии к ним. Пары расписания и новости останутся – без предмета. Вернуть не получится. Если предмет просто закончился – лучше «В архив».`,
		{ title: `Удалить «${s.name}»?`, ok: 'Удалить навсегда', danger: true }
	);
	if (!ok) return;
	try {
		await del(`/api/subjects/${s.id}`);
		await loadSubjects();
		toast('Предмет удалён', 'ok');
		goto('/subjects', { replaceState: true });
	} catch (e) {
		toastError(e);
	}
}
