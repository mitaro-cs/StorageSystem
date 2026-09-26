import { get } from './api';
import { can } from './session.svelte';
import type { ModSummary } from './types';

/** Сколько ждёт модератора: жалобы и материалы на проверке (число в меню). */
export const moderation = $state({ reports: 0, pending: 0 });

/** Раздел «Модерация» — тем, кто модерирует хоть в одной группе (и в режиме управления). */
export function canModerate(): boolean {
	return can('moderate_content');
}

export async function refreshModeration() {
	if (!canModerate()) return;
	try {
		const s = await get<ModSummary>('/api/moderation/summary');
		moderation.reports = s.reports;
		moderation.pending = s.pending;
	} catch {
		/* нет сети — число обновится позже */
	}
}
