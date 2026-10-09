import { preloadCode } from '$app/navigation';
import { request } from './api';
import { cacheKeys, peek, put } from './cache';

const KEY = 'gb-warm';

/**
 * Разделы заранее (1.0.1): после запуска – запросы, которые разделы делали прошлый раз, по два за
 * раз. Переход в раздел тогда рисуется сразу, без заглушки и рывка. Адреса запоминаются, когда
 * вкладку скрывают (ответы лежат в lib/cache.ts с приставкой «api:»).
 */
export async function warm(): Promise<void> {
	const save = () => {
		try {
			localStorage.setItem(
				KEY,
				JSON.stringify(
					cacheKeys()
						.filter((k) => k.startsWith('api:'))
						.map((k) => k.slice(4))
						.slice(-12)
				)
			);
		} catch {
			/* не запоминаем */
		}
	};
	addEventListener('pagehide', save);
	addEventListener('visibilitychange', () => document.visibilityState === 'hidden' && save());
	// Код разделов и подсказки к ним – тоже заранее: иначе первый переход ждёт код через туннель, а
	// подсказка появляется кадром позже страницы и сдвигает её вниз.
	for (const path of [
		'/',
		'/news',
		'/homework',
		'/schedule',
		'/subjects',
		'/materials',
		'/profile'
	])
		preloadCode(path).catch(() => {});
	import('$lib/tour/Tip.svelte').catch(() => {});
	let list: string[];
	try {
		list = JSON.parse(localStorage.getItem(KEY) ?? '[]');
	} catch {
		return;
	}
	const queue = list.slice().reverse();
	const next = async (): Promise<void> => {
		const path = queue.shift();
		if (!path) return;
		// Ответ ляжет туда же, куда api.ts кладёт ответы разделов, и покажется сразу.
		if (!peek('api:' + path))
			await request(path).then(
				(d) => put('api:' + path, d),
				() => {}
			);
		return next();
	};
	await Promise.all([next(), next()]);
}
