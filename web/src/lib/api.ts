import { goto } from '$app/navigation';
import { untrack } from 'svelte';
import { pwa } from './pwa.svelte';
import { dropEarly, takeEarly } from './early';
import { within } from './race';
import {
	enabled as offlineEnabled,
	enqueue,
	hasCopy,
	offline,
	queueable,
	resolveLocal
} from './offline/engine';

export class ApiError extends Error {
	constructor(
		public status: number,
		public code: string,
		message: string,
		public details?: Record<string, unknown>
	) {
		super(message);
	}
}

type Fetch = typeof fetch;

function readCookie(suffix: string): string | null {
	for (const part of document.cookie.split(';')) {
		const [name, ...rest] = part.trim().split('=');
		if (name === suffix || name === `__Host-${suffix}`) return rest.join('=');
	}
	return null;
}

async function csrfToken(f: Fetch): Promise<string> {
	let t = readCookie('gb_csrf');
	if (!t) {
		await f('/api/health');
		t = readCookie('gb_csrf');
	}
	return t ?? '';
}

export interface RequestOptions {
	method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
	body?: unknown;
	form?: FormData;
	signal?: AbortSignal;
	fetch?: Fetch;
	/** Не перенаправлять на вход при 401 (страницы входа и регистрации). */
	anonymous?: boolean;
	/** Не перенаправлять на вход при 401, а в остальном как обычно: вход решает другой запрос. */
	quiet401?: boolean;
}

/**
 * Слабая сеть: чтение, на которое есть ответ в копии на устройстве, ждём не дольше этого — дальше
 * показываем копию, а свежий ответ приходит следом и обновляет страницу.
 */
const SLOW_MS = 1500;
/** После своего изменения копию не показываем: в ней его ещё нет. */
const AFTER_WRITE_MS = 10_000;
let lastWrite = 0;
/** Свежие ответы, пришедшие позже копии: страница перечитает себя и получит их без запроса. */
const fresh = new Map<string, { data: unknown; at: number }>();

/** Есть свежий ответ на этот запрос, пришедший после показанной копии. */
export function hasFresh(path: string): boolean {
	return fresh.has(path);
}

/** Запрос к API по сети: JSON, CSRF для мутаций, ошибки → ApiError с текстом по-русски. */
export async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
	const f = opts.fetch ?? fetch;
	const method = opts.method ?? (opts.body !== undefined || opts.form ? 'POST' : 'GET');
	const headers: Record<string, string> = { Accept: 'application/json' };
	if (method !== 'GET') headers['X-CSRF-Token'] = await csrfToken(f);
	let body: BodyInit | undefined;
	if (opts.form) body = opts.form;
	else if (opts.body !== undefined) {
		headers['Content-Type'] = 'application/json';
		body = JSON.stringify(opts.body);
	}
	// Чтение без ответа дольше 10 секунд считаем отсутствием сети: покажем сохранённое. Если сервер
	// уже недоступен, ждём меньше — чтобы сохранённое открывалось сразу. untrack: запрос из $effect
	// не должен зависеть от состояния сети — иначе страница перечитывает себя при каждом его изменении.
	const wait = untrack(() => pwa.offline) ? 3_000 : 10_000;
	const signal =
		opts.signal ??
		(method === 'GET' && typeof AbortSignal.timeout === 'function'
			? AbortSignal.timeout(wait)
			: undefined);
	// Первый экран: app.html уже отправил этот запрос, пока грузился код.
	const early = method === 'GET' && !opts.signal ? takeEarly(path) : undefined;
	let res: Response;
	try {
		const got = early ? await within(early, wait) : null;
		if (early && got === null) throw new DOMException('Сервер не ответил', 'TimeoutError');
		res = got?.ok
			? got.value
			: await f(path, { method, headers, body, signal, credentials: 'same-origin' });
	} catch (e) {
		if ((e as Error).name === 'AbortError' && opts.signal?.aborted) throw e;
		throw new ApiError(0, 'network', 'Нет связи с сервером. Проверьте интернет');
	}
	// Ответ не от сервера группы: компьютер хоста выключен, и туннель отвечает своей страницей
	// ошибки. Это то же, что нет сети: показываем сохранённое, действия уходят в очередь.
	if (!res.headers.has('X-Groupbase')) {
		throw new ApiError(0, 'network', 'Сервер группы сейчас выключен — попробуйте позже');
	}
	pwa.offline = false;
	if (res.status === 401) dropEarly();
	if (method !== 'GET' && res.ok) lastWrite = Date.now();
	const isJson = res.headers.get('Content-Type')?.startsWith('application/json');
	const data = isJson ? await res.json() : null;
	if (!res.ok) {
		// Сайт сейчас работает на другом компьютере хоста: здесь — страница ожидания.
		if (data?.error === 'standby' && typeof window !== 'undefined') {
			if (location.pathname !== '/standby') location.replace('/standby');
		} else if (
			res.status === 401 &&
			!opts.anonymous &&
			!opts.quiet401 &&
			typeof window !== 'undefined'
		) {
			const next = location.pathname + location.search;
			await goto(`/login?next=${encodeURIComponent(next)}`, { replaceState: true });
		}
		throw new ApiError(
			res.status,
			data?.error ?? 'error',
			data?.message ?? `Ошибка ${res.status}`,
			data?.details
		);
	}
	return data as T;
}

/**
 * Запрос с офлайн-режимом: без сети чтение идёт из копии на устройстве, а часть действий
 * (отметки, комментарии, публикации) встаёт в очередь и уйдёт на сервер позже.
 */
export async function api<T>(path: string, opts: RequestOptions = {}): Promise<T> {
	const method = opts.method ?? (opts.body !== undefined || opts.form ? 'POST' : 'GET');
	const local = !opts.anonymous && !opts.form && untrack(offlineEnabled);
	// Объекты, созданные без сети, есть только на устройстве (временный отрицательный id).
	if (local && (!navigator.onLine || /\/-\d+(\/|$|\?)/.test(path)))
		return fallback<T>(method, path, opts.body);
	const later = method === 'GET' ? fresh.get(path) : undefined;
	if (later) {
		fresh.delete(path);
		if (Date.now() - later.at < 30_000) return later.data as T;
	}
	const net = request<T>(path, opts);
	if (local && method === 'GET' && !opts.signal && Date.now() - lastWrite > AFTER_WRITE_MS) {
		const quick = await within(net, SLOW_MS);
		const copy = quick === null ? await localCopy(path) : undefined;
		if (copy !== undefined) {
			net.then(
				(data) => {
					if (JSON.stringify(data) === JSON.stringify(copy)) return;
					fresh.set(path, { data, at: Date.now() });
					offline.version++;
				},
				() => {
					/* сеть так и не ответила — копия уже на экране */
				}
			);
			return copy as T;
		}
	}
	try {
		return await net;
	} catch (e) {
		if (local && e instanceof ApiError && e.code === 'network')
			return fallback<T>(method, path, opts.body);
		throw e;
	}
}

/** Ответ из копии на устройстве, если она есть и знает этот запрос. */
async function localCopy(path: string): Promise<unknown> {
	try {
		return (await hasCopy()) ? await resolveLocal(path) : undefined;
	} catch {
		return undefined;
	}
}

async function fallback<T>(method: string, path: string, body: unknown): Promise<T> {
	pwa.offline = true;
	if (method === 'GET') {
		const r = await resolveLocal(path);
		if (r !== undefined) return r as T;
		throw new ApiError(
			0,
			'offline',
			'Без интернета это недоступно — откройте, когда появится сеть'
		);
	}
	if (queueable(method, path)) return (await enqueue(method, path, body)) as T;
	throw new ApiError(0, 'offline', 'Нужен интернет: это действие без сети не сохранить');
}

export const get = <T>(path: string, opts: RequestOptions = {}) => api<T>(path, opts);
export const post = <T>(path: string, body?: unknown, opts: RequestOptions = {}) =>
	api<T>(path, { ...opts, method: 'POST', body: body ?? {} });
export const put = <T>(path: string, body?: unknown) => api<T>(path, { method: 'PUT', body });
export const patch = <T>(path: string, body?: unknown) => api<T>(path, { method: 'PATCH', body });
export const del = <T>(path: string, body?: unknown) => api<T>(path, { method: 'DELETE', body });

/** Строка запроса без пустых параметров. */
export function qs(params: Record<string, string | number | null | undefined | boolean>): string {
	const p = new URLSearchParams();
	for (const [k, v] of Object.entries(params)) {
		if (v !== null && v !== undefined && v !== '') p.set(k, String(v));
	}
	const s = p.toString();
	return s ? `?${s}` : '';
}
