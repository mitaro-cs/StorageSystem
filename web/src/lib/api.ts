import { goto } from '$app/navigation';
import { markCached } from './pwa.svelte';

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
}

/** Запрос к API: JSON, CSRF-заголовок для мутаций, ошибки → ApiError с текстом по-русски. */
export async function api<T>(path: string, opts: RequestOptions = {}): Promise<T> {
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
	let res: Response;
	try {
		res = await f(path, { method, headers, body, signal: opts.signal, credentials: 'same-origin' });
	} catch (e) {
		if ((e as Error).name === 'AbortError') throw e;
		throw new ApiError(0, 'network', 'Нет связи с сервером. Проверьте интернет');
	}
	markCached(res);
	const isJson = res.headers.get('Content-Type')?.startsWith('application/json');
	const data = isJson ? await res.json() : null;
	if (!res.ok) {
		if (res.status === 401 && !opts.anonymous && typeof window !== 'undefined') {
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
