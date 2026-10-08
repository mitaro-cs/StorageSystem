import { del, get, patch } from '$lib/api';
import { loadSubjects } from '$lib/data.svelte';
import { plural } from '$lib/format';
import { toast, toastError } from '$lib/toasts.svelte';
import type { Semester } from '$lib/types';
import { ask, askText } from '$lib/ui/ask.svelte';

// Архивы прошлых семестров (0.9.7): список, переименование, «Вернуть предметы».

export interface Semesters {
	items: Semester[];
	/** Название по умолчанию для нового архива: «Весенний семестр 2026». */
	suggested: string;
}

export const loadSemesters = (groupId: number) =>
	get<Semesters>(`/api/groups/${groupId}/semesters`);

export async function renameSemester(s: Semester, done?: () => void): Promise<void> {
	const name = await askText('Как будет называться архив?', {
		title: 'Переименовать архив',
		value: s.name,
		ok: 'Сохранить',
		maxlength: 80
	});
	if (!name || name === s.name) return;
	try {
		await patch(`/api/semesters/${s.id}`, { name });
		toast('Архив переименован', 'ok');
		done?.();
	} catch (e) {
		toastError(e);
	}
}

export async function restoreSemester(s: Semester, done?: () => void): Promise<void> {
	const n = s.subjects;
	const ok = await ask(
		`${n} ${plural(n, ['предмет', 'предмета', 'предметов'])} из «${s.name}» снова станут текущими, а архив исчезнет. Задания и файлы на месте.`,
		{ title: 'Вернуть предметы?', ok: 'Вернуть' }
	);
	if (!ok) return;
	try {
		await del(`/api/semesters/${s.id}`);
		await loadSubjects();
		toast('Предметы вернулись из архива', 'ok');
		done?.();
	} catch (e) {
		toastError(e);
	}
}
