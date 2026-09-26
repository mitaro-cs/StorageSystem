/**
 * Запросы первого экрана, которые app.html отправил сразу, ещё до загрузки кода интерфейса: на
 * телефоне через туннель каждый запрос — это задержка, а так профиль, предметы и «Сегодня» идут
 * параллельно с загрузкой кода. Интерфейс забирает готовый ответ вместо нового запроса.
 */

interface Early {
	/** Когда отправлены. */
	at: number;
	req: Record<string, Promise<Response> | undefined>;
}

/** Дольше ответ не годится: данные могли измениться. */
const FRESH_MS = 20_000;

/** Ключ в localStorage: адрес «Сегодня» с группой — его app.html запрашивает заранее. */
export const TODAY_KEY = 'gb-today';

function early(): Early | undefined {
	return (globalThis as { __gbEarly?: Early }).__gbEarly;
}

/** Ответ, запрошенный заранее, — один раз; undefined — такого запроса не было. */
export function takeEarly(path: string): Promise<Response> | undefined {
	const e = early();
	const p = e?.req[path];
	if (!e || !p) return undefined;
	delete e.req[path];
	return Date.now() - e.at > FRESH_MS ? undefined : p;
}

/** Забыть все ранние ответы (сессия кончилась — они от прежнего входа). */
export function dropEarly() {
	const e = early();
	if (e) e.req = {};
}
