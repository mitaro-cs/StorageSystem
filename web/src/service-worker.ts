/// <reference types="@sveltejs/kit" />
/// <reference no-default-lib="true"/>
/// <reference lib="esnext" />
/// <reference lib="webworker" />

import { build, files, version } from '$service-worker';

const sw = self as unknown as ServiceWorkerGlobalScope;
const SHELL = `shell-${version}`;
const DATA = 'data-v1';
/** Файлы материалов: открытые и скачанные заранее (см. lib/offline/files.ts). */
const FILES = 'files-v1';
const INDEX = '/index.html';
/** Метка в кеше оболочки: все файлы этой версии сохранены — приложение открывается без сети. */
const COMPLETE = '/__complete';

/** Статика (шрифты, иконки, манифест). Предсжатые копии не нужны. */
const STATIC = files.filter((f) => !/\.(br|gz)$/.test(f));
/** Оболочка приложения: код, стили, статика. */
const ASSETS = new Set([...build, ...STATIC, INDEX]);
/**
 * Просмотр PDF (pdf.js, ~2 МБ) нужен не всем и не каждый день: его не качаем при каждом обновлении
 * приложения, а сохраняем при первом открытии PDF — дальше он работает и без сети.
 */
const LAZY = /pdf/i;
/**
 * При установке — только то, без чего приложение не открыть: страница, шрифты, иконки. Код разделов
 * сохраняется, когда его загружают страницы, а остальное — фоном, по два файла, когда первый экран
 * уже открыт (warm). Раньше при установке качались сотни файлов разом: на телефоне через туннель
 * это забивало связь, и всё, что человек делал в первые минуты, стояло в очереди за ними.
 */
const CORE = [INDEX, ...STATIC.filter((a) => !LAZY.test(a) && !/\.txt$/.test(a))];
const WARM = build.filter((a) => !LAZY.test(a));
/** Код с хешем в имени не меняется: то, что уже скачано прежней версией, берём из её кеша. */
const IMMUTABLE = '/_app/immutable/';

sw.addEventListener('install', (event) => {
	event.waitUntil(
		caches.open(SHELL).then(async (cache) => {
			await reuse(cache);
			await cache.addAll(CORE);
		})
	);
	sw.skipWaiting();
});

/** Неизменные файлы новой версии, которые уже есть в кеше прежней, — копируем без сети. */
async function reuse(cache: Cache) {
	const wanted = new Set(build.filter((a) => a.startsWith(IMMUTABLE)));
	for (const name of await caches.keys()) {
		if (!name.startsWith('shell-') || name === SHELL) continue;
		const prev = await caches.open(name);
		for (const req of await prev.keys()) {
			const path = new URL(req.url).pathname;
			if (!wanted.has(path)) continue;
			const res = await prev.match(req);
			if (res) await cache.put(path, res);
			wanted.delete(path);
		}
	}
}

sw.addEventListener('activate', (event) => {
	event.waitUntil(
		caches
			.keys()
			.then((keys) => {
				// Прежнюю оболочку держим до следующего обновления: открытая старая страница ещё может
				// догружать свой код.
				const previous = keys.filter((k) => k.startsWith('shell-') && k !== SHELL).slice(-1);
				const keep = new Set([SHELL, DATA, FILES, ...previous]);
				return Promise.all(keys.filter((k) => !keep.has(k)).map((k) => caches.delete(k)));
			})
			.then(() => sw.clients.claim())
	);
});

/** Докачать остальную оболочку фоном — по два файла, чтобы не мешать самому приложению. */
let warming: Promise<void> | null = null;

async function warm() {
	const cache = await caches.open(SHELL);
	if (await cache.match(COMPLETE)) return;
	await reuse(cache);
	const have = new Set((await cache.keys()).map((r) => new URL(r.url).pathname));
	const todo = WARM.filter((a) => !have.has(a));
	let next = 0;
	let broken = false;
	const worker = async () => {
		while (!broken && next < todo.length) {
			const path = todo[next++];
			try {
				const res = await fetch(path);
				// Сервер выключен (ответ туннеля) или ошибка — докачаем в следующий раз.
				if (!res.ok || !fromServer(res)) broken = true;
				else await cache.put(path, res);
			} catch {
				broken = true;
			}
		}
	};
	await Promise.all([worker(), worker()]);
	if (!broken) await cache.put(COMPLETE, new Response(version));
}

/** При выходе из аккаунта сохранённые данные удаляются с устройства. */
sw.addEventListener('message', (event) => {
	if (event.data === 'logout')
		event.waitUntil(Promise.all([caches.delete(DATA), caches.delete(FILES)]));
	if (event.data === 'warm') {
		warming ??= warm()
			.catch(() => {})
			.finally(() => (warming = null));
		event.waitUntil(warming);
	}
});

/**
 * Ответил ли сам сервер группы. Когда компьютер хоста выключен, туннель отвечает своей страницей
 * ошибки — такой ответ нельзя ни показывать вместо приложения, ни сохранять.
 */
const fromServer = (res: Response) => res.headers.has('X-Groupbase');

/** Файл по id не меняется: сначала сохранённая копия, иначе сеть — и запоминаем. */
async function fileFirst(req: Request, path: string): Promise<Response> {
	const cache = await caches.open(FILES);
	const hit = await cache.match(path, { ignoreSearch: true });
	if (hit) return hit;
	const res = await fetch(req);
	if (res.ok && res.status === 200 && fromServer(res)) cache.put(path, res.clone());
	return res;
}

/**
 * Файл оболочки: из кеша этой версии, код с хешем в имени — из кеша любой версии, иначе из сети — и
 * сохраняем для следующего раза. Статику (иконки, шрифты) — только своей версии: её имя не меняется.
 */
async function asset(req: Request, path: string): Promise<Response> {
	const shell = await caches.open(SHELL);
	const hit =
		(await shell.match(path)) ??
		(path.startsWith(IMMUTABLE) ? await caches.match(path) : undefined);
	if (hit) return hit;
	const res = await fetch(req);
	if (res.ok && res.status === 200 && fromServer(res) && (ASSETS.has(path) || LAZY.test(path)))
		shell.put(path, res.clone());
	return res;
}

/**
 * Страница. Когда вся оболочка этой версии сохранена — сразу из кеша: приложение открывается без
 * ожидания сети (через туннель это заметная задержка), а новая версия приходит со следующим service
 * worker. Пока сохранено не всё — из сети, а без неё — сохранённая.
 */
let complete = false;

async function page(req: Request): Promise<Response> {
	// Только своя версия: в кеше прежней лежит прежняя страница, с ней обновление не пришло бы.
	const shell = await caches.open(SHELL);
	complete ||= !!(await shell.match(COMPLETE));
	const saved = complete ? await shell.match(INDEX) : undefined;
	if (saved) return saved;
	try {
		const res = await fetch(req);
		// Перенаправление приходит «непрозрачным» — заголовков не видно, но это ответ сервера, и
		// браузер сам пойдёт по нему. Подменить его оболочкой — значит зациклить переход.
		if (res.type === 'opaqueredirect' || fromServer(res)) return res;
		return (await savedPage(shell)) ?? res;
	} catch {
		return (await savedPage(shell)) ?? Response.error();
	}
}

/**
 * Сервер недоступен, а эта версия ещё не сохранена целиком (только что обновилась): прежняя версия,
 * у которой в кеше весь код, откроется, а новая — нет.
 */
async function savedPage(shell: Cache): Promise<Response | undefined> {
	if (!complete) {
		for (const name of (await caches.keys()).reverse()) {
			if (!name.startsWith('shell-') || name === SHELL) continue;
			const prev = await caches.open(name);
			if (await prev.match(COMPLETE)) return prev.match(INDEX);
		}
	}
	return shell.match(INDEX);
}

sw.addEventListener('fetch', (event) => {
	const req = event.request;
	const url = new URL(req.url);
	if (req.method !== 'GET' || url.origin !== location.origin) return;

	if (req.mode === 'navigate') {
		// Переходы на /api/… (вход окна хоста, скачивание архивов) — это не страницы приложения:
		// пусть идут прямо на сервер.
		if (!url.pathname.startsWith('/api/')) event.respondWith(page(req));
		return;
	}
	// Код прежней версии тоже ищем в кеше: открытая до обновления страница догружает свой.
	if (ASSETS.has(url.pathname) || url.pathname.startsWith(IMMUTABLE)) {
		event.respondWith(asset(req, url.pathname));
		return;
	}
	if (url.pathname.startsWith('/api/avatars/')) {
		event.respondWith(
			caches.open(DATA).then(async (c) => {
				const hit = await c.match(req);
				if (hit) return hit;
				const res = await fetch(req);
				if (res.ok && fromServer(res)) c.put(req, res.clone());
				return res;
			})
		);
		return;
	}
	if (/^\/api\/files\/\d+$/.test(url.pathname)) {
		event.respondWith(fileFirst(req, url.pathname));
	}
});

/** Push-уведомление: содержимое расшифровал браузер, показываем и говорим открытым вкладкам. */
sw.addEventListener('push', (event) => {
	let data: { title?: string; body?: string; url?: string; tag?: string };
	try {
		data = event.data?.json() ?? {};
	} catch {
		data = { body: event.data?.text() };
	}
	event.waitUntil(
		Promise.all([
			sw.registration.showNotification(data.title || 'groupbase', {
				body: data.body ?? '',
				tag: data.tag,
				icon: '/icons/icon-192.png?v=2',
				badge: '/icons/badge-96.png?v=2',
				lang: 'ru',
				data: { url: data.url ?? '/' }
			}),
			sw.clients
				.matchAll({ type: 'window' })
				.then((all) => all.forEach((c) => c.postMessage({ type: 'push' })))
		])
	);
});

/** Нажатие на уведомление: открываем нужную страницу в уже открытом окне или в новом. */
sw.addEventListener('notificationclick', (event) => {
	event.notification.close();
	const url = new URL(event.notification.data?.url ?? '/', sw.location.origin).href;
	event.waitUntil(
		(async () => {
			const all = await sw.clients.matchAll({ type: 'window', includeUncontrolled: true });
			for (const c of all) {
				if (new URL(c.url).origin === sw.location.origin) {
					await c.focus();
					return c.navigate(url).catch(() => sw.clients.openWindow(url));
				}
			}
			return sw.clients.openWindow(url);
		})()
	);
});
