import { request } from './api';
import type { Person } from './types';

/**
 * Кто сейчас на сайте (1.0.2): люди из своих групп с открытой страницей. Перечитывается по событию
 * «presence» живых обновлений (lib/live.ts), без потока – раз в 15 секунд.
 */
export const presence = $state({ online: [] as Person[], loaded: false });

let at = 0;

export async function loadPresence(force = false): Promise<void> {
	if (!force && Date.now() - at < 3000) return;
	at = Date.now();
	try {
		const r = await request<{ online: Person[] }>('/api/presence', { quiet401: true });
		presence.online = r.online;
		presence.loaded = true;
	} catch {
		/* нет связи – покажем, что знали */
	}
}

export const isOnline = (id: number) => presence.online.some((p) => p.id === id);
