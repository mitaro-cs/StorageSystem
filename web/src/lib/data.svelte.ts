import { get, type RequestOptions } from './api';
import type { Subject } from './types';

/** Предметы для боковой панели и выпадающих списков; обновляются после изменений. */
export const subjects = $state<{ list: Subject[]; loaded: boolean }>({ list: [], loaded: false });

export async function loadSubjects(opts?: RequestOptions): Promise<Subject[]> {
	subjects.list = await get<Subject[]>('/api/subjects', opts);
	subjects.loaded = true;
	return subjects.list;
}

export function subjectById(id: number): Subject | undefined {
	return subjects.list.find((s) => s.id === id);
}

/** Активные предметы, закреплённые — первыми. mine — без скрытых у себя (другая подгруппа). */
export function sortedSubjects(groupId: number | null, mine = false): Subject[] {
	return subjects.list
		.filter(
			(s) =>
				!s.archived &&
				(!mine || s.mine !== false) &&
				(groupId === null || s.groups.some((g) => g.id === groupId))
		)
		.sort((a, b) => Number(b.pinned) - Number(a.pinned) || a.name.localeCompare(b.name, 'ru'));
}
