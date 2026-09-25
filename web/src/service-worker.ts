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

/** Оболочка приложения: код, стили, шрифты, иконки. Предсжатые копии не нужны. */
const ASSETS = [...build, ...files.filter((f) => !/\.(br|gz)$/.test(f)), INDEX];
/**
 * Просмотр PDF (pdf.js, ~2 МБ) нужен не всем и не каждый день: его не качаем при каждом обновлении
 * приложения, а сохраняем при первом открытии PDF — дальше он работает и без сети.
 */
const LAZY = /pdf/i;
const PRECACHE = ASSETS.filter((a) => !LAZY.test(a));

sw.addEventListener('install', (event) => {
	event.waitUntil(caches.open(SHELL).then((c) => c.addAll(PRECACHE)));
	sw.skipWaiting();
});

sw.addEventListener('activate', (event) => {
	event.waitUntil(
		caches
			.keys()
			.then((keys) =>
				Promise.all(
					keys.filter((k) => ![SHELL, DATA, FILES].includes(k)).map((k) => caches.delete(k))
				)
			)
			.then(() => sw.clients.claim())
	);
});

/** При выходе из аккаунта сохранённые данные удаляются с устройства. */
sw.addEventListener('message', (event) => {
	if (event.data === 'logout')
		event.waitUntil(Promise.all([caches.delete(DATA), caches.delete(FILES)]));
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

/** Страница: из сети, а если сервер недоступен — оболочка приложения из кеша. */
async function page(req: Request): Promise<Response> {
	try {
		const res = await fetch(req);
		// Перенаправление приходит «непрозрачным» — заголовков не видно, но это ответ сервера, и
		// браузер сам пойдёт по нему. Подменить его оболочкой — значит зациклить переход.
		if (res.type === 'opaqueredirect' || fromServer(res)) return res;
		return (await caches.match(INDEX)) ?? res;
	} catch {
		return (await caches.match(INDEX)) ?? Response.error();
	}
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
	if (ASSETS.includes(url.pathname)) {
		event.respondWith(
			caches.match(url.pathname).then(
				(hit) =>
					hit ??
					fetch(req).then((res) => {
						if (res.ok && LAZY.test(url.pathname)) {
							const copy = res.clone();
							caches.open(SHELL).then((c) => c.put(url.pathname, copy));
						}
						return res;
					})
			)
		);
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
				icon: '/icons/icon-192.png',
				badge: '/icons/badge-96.png',
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
