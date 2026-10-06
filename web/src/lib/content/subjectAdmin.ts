import { goto } from '$app/navigation';
import { del, post } from '$lib/api';
import { loadSubjects } from '$lib/data.svelte';
import { toast, toastError } from '$lib/toasts.svelte';
import type { Subject } from '$lib/types';
import { ask, askText } from '$lib/ui/ask.svelte';

/**
 * Редкие действия старосты с предметом (0.7) – из меню «…» страницы предмета, код грузится по
 * нажатию: разделить на подгруппы и удалить насовсем.
 */

/** «Английский язык» → «№1» (этот, со всем содержимым) и пустые «№2»…; каждый выберет свою. */
export async function splitSubject(s: Subject) {
	const v = await askText(
		'Сколько подгрупп? Этот предмет станет №1 – со всеми заданиями и файлами, остальные появятся пустыми. Каждый выберет свою, чужие у него скроются.',
		{
			title: 'Разделить на подгруппы',
			ok: 'Разделить',
			value: '2',
			inputmode: 'numeric',
			maxlength: 1
		}
	);
	const count = Number(v);
	if (!v) return;
	if (!Number.isInteger(count) || count < 2 || count > 6)
		return toast('Подгрупп – от 2 до 6', 'error');
	try {
		await post<Subject[]>(`/api/subjects/${s.id}/subgroups`, { count });
		await loadSubjects();
		toast(`Готово: ${count} подгруппы – каждый выберет свою`, 'ok');
		goto('/subjects');
	} catch (e) {
		toastError(e);
	}
}

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
