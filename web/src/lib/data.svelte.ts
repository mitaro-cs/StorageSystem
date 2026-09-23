import { get } from './api';
import type { Subject } from './types';

/** Предметы для боковой панели и выпадающих списков; обновляются после изменений. */
export const subjects = $state<{ list: Subject[]; loaded: boolean }>({ list: [], loaded: false });

export async function loadSubjects(): Promise<Subject[]> {
	subjects.list = await get<Subject[]>('/api/subjects');
	subjects.loaded = true;
	return subjects.list;
}

export function subjectById(id: number): Subject | undefined {
	return subjects.list.find((s) => s.id === id);
}

/** Активные предметы, закреплённые — первыми. */
export function sortedSubjects(groupId: number | null): Subject[] {
	return subjects.list
		.filter((s) => !s.archived && (groupId === null || s.groups.some((g) => g.id === groupId)))
		.sort((a, b) => Number(b.pinned) - Number(a.pinned) || a.name.localeCompare(b.name, 'ru'));
}
