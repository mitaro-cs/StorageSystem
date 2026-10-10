import { goto } from '$app/navigation';
import { del, get, patch, post } from '$lib/api';
import { loadSubjects } from '$lib/data.svelte';
import { plural } from '$lib/format';
import { toast, toastError } from '$lib/toasts.svelte';
import type { Semester, Subject } from '$lib/types';
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
	const ok = n
		? await ask(
				`${n} ${plural(n, ['предмет', 'предмета', 'предметов'])} из «${s.name}» снова станут текущими, а архив исчезнет. Задания и файлы на месте.`,
				{ title: 'Вернуть предметы?', ok: 'Вернуть' }
			)
		: await ask(`В «${s.name}» нет предметов – убрать его?`, {
				title: 'Пустой архив',
				ok: 'Убрать'
			});
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

/** Прошлый семестр вручную (1.0.2): пустой архив, предметы и файлы добавляются потом. */
export async function createManualSemester(groupId: number, done?: () => void): Promise<void> {
	let suggested = '';
	try {
		suggested = (await loadSemesters(groupId)).suggested;
	} catch {
		/* без подсказки */
	}
	const name = await askText('Как назвать прошлый семестр? Например, «Осенний семестр 2024».', {
		title: 'Новый архив',
		value: suggested,
		ok: 'Создать',
		maxlength: 80
	});
	if (!name) return;
	try {
		await post(`/api/groups/${groupId}/semesters`, { name, manual: true });
		toast('Архив создан – добавьте в него предметы', 'ok');
		done?.();
	} catch (e) {
		toastError(e);
	}
}

/** Предмет прямо в архив: после создания – на его страницу, загружать файлы и конспекты. */
export async function addArchivedSubject(s: Semester): Promise<void> {
	const name = await askText(`Какой предмет добавить в «${s.name}»?`, {
		title: 'Предмет в архив',
		ok: 'Добавить',
		maxlength: 120
	});
	if (!name) return;
	try {
		const subject = await post<Subject>(`/api/semesters/${s.id}/subjects`, { name });
		await loadSubjects();
		toast('Предмет в архиве – загрузите в него файлы и конспекты', 'ok');
		goto(`/subjects/${subject.id}`);
	} catch (e) {
		toastError(e);
	}
}
