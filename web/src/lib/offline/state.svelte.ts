/** Состояние офлайн-режима для интерфейса (см. engine.ts). */
export const offline = $state({
	ready: false,
	syncing: false,
	lastSync: 0,
	pending: 0,
	/** Растёт, когда данные на устройстве поменялись: страницы перечитывают себя. */
	version: 0,
	counts: { news: 0, homework: 0, materials: 0 }
});
