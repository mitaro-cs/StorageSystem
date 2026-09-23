/// <reference types="@sveltejs/kit" />
/// <reference no-default-lib="true"/>
/// <reference lib="esnext" />
/// <reference lib="webworker" />

import { build, files, version } from '$service-worker';

const sw = self as unknown as ServiceWorkerGlobalScope;
const SHELL = `shell-${version}`;
const DATA = 'data-v1';
const INDEX = '/index.html';

/** Оболочка приложения: код, стили, шрифты, иконки. Предсжатые копии не нужны. */
const ASSETS = [...build, ...files.filter((f) => !/\.(br|gz)$/.test(f)), INDEX];

/** Эти GET-запросы API доступны офлайн: сначала сеть, при её отсутствии — последний ответ. */
const OFFLINE_API = [
	/^\/api\/me$/,
	/^\/api\/today/,
	/^\/api\/news/,
	/^\/api\/homework/,
	/^\/api\/subjects/
];

sw.addEventListener('install', (event) => {
	event.waitUntil(caches.open(SHELL).then((c) => c.addAll(ASSETS)));
	sw.skipWaiting();
});

sw.addEventListener('activate', (event) => {
	event.waitUntil(
		caches
			.keys()
			.then((keys) =>
				Promise.all(keys.filter((k) => k !== SHELL && k !== DATA).map((k) => caches.delete(k)))
			)
			.then(() => sw.clients.claim())
	);
});

/** При выходе из аккаунта сохранённые данные удаляются с устройства. */
sw.addEventListener('message', (event) => {
	if (event.data === 'logout') event.waitUntil(caches.delete(DATA));
});

async function networkFirst(req: Request): Promise<Response> {
	const cache = await caches.open(DATA);
	try {
		const res = await fetch(req);
		if (res.ok) cache.put(req, res.clone());
		return res;
	} catch {
		const hit = await cache.match(req);
		if (!hit) throw new Error('offline');
		const headers = new Headers(hit.headers);
		headers.set('X-From-Cache', '1');
		return new Response(hit.body, { status: hit.status, headers });
	}
}

sw.addEventListener('fetch', (event) => {
	const req = event.request;
	const url = new URL(req.url);
	if (req.method !== 'GET' || url.origin !== location.origin) return;

	if (req.mode === 'navigate') {
		event.respondWith(
			fetch(req).catch(async () => (await caches.match(INDEX)) ?? Response.error())
		);
		return;
	}
	if (ASSETS.includes(url.pathname)) {
		event.respondWith(caches.match(url.pathname).then((hit) => hit ?? fetch(req)));
		return;
	}
	if (url.pathname.startsWith('/api/avatars/')) {
		event.respondWith(
			caches.open(DATA).then(async (c) => {
				const hit = await c.match(req);
				if (hit) return hit;
				const res = await fetch(req);
				if (res.ok) c.put(req, res.clone());
				return res;
			})
		);
		return;
	}
	if (OFFLINE_API.some((r) => r.test(url.pathname))) {
		event.respondWith(networkFirst(req));
	}
});
