import { request } from '$lib/api';
import type { FileInfo } from '$lib/types';
import type { Snapshot } from './local';

/**
 * Файлы материалов и вложений на устройстве. Service worker отдаёт их из этого кеша, когда нет
 * сети, и сам сохраняет всё, что человек открывал. Заранее скачиваем по выбранному правилу.
 */

export const FILES_CACHE = 'files-v1';
const POLICY = 'gb-files-policy';
const SMALL = 20 * 1024 * 1024;

/** all – все файлы, small – до 20 МБ, opened – только открытые. */
export type FilesPolicy = 'all' | 'small' | 'opened';

export function filesPolicy(): FilesPolicy {
	try {
		const v = localStorage.getItem(POLICY);
		return v === 'all' || v === 'opened' ? v : 'small';
	} catch {
		return 'small';
	}
}

export function setFilesPolicy(p: FilesPolicy) {
	try {
		localStorage.setItem(POLICY, p);
	} catch {
		/* не запоминаем */
	}
}

const available = () => typeof caches !== 'undefined';

function wanted(s: Snapshot): FileInfo[] {
	const out = new Map<number, FileInfo>();
	for (const m of s.materials) if (m.file && m.file.id > 0) out.set(m.file.id, m.file);
	for (const h of s.homework) for (const f of h.attachments) if (f.id > 0) out.set(f.id, f);
	for (const n of s.news) for (const f of n.attachments ?? []) if (f.id > 0) out.set(f.id, f);
	return [...out.values()];
}

let prefetching = false;

export async function prefetchFiles(s: Snapshot) {
	const policy = filesPolicy();
	if (!available() || prefetching || !navigator.onLine) return;
	prefetching = true;
	try {
		const cache = await caches.open(FILES_CACHE);
		for (const f of policy === 'opened' ? [] : wanted(s)) {
			if (policy === 'small' && f.size > SMALL) continue;
			const url = `/api/files/${f.id}`;
			if (await cache.match(url, { ignoreSearch: true })) continue;
			try {
				const res = await fetch(url, { credentials: 'same-origin' });
				if (res.ok) await cache.put(url, res);
			} catch {
				break;
			}
		}
		await shareMissing(cache);
	} finally {
		prefetching = false;
	}
}

/**
 * Файлы, которых нет на сервере (восстановили копию без них, компьютер хоста потерял), но есть
 * здесь: досылаем. Сервер сверяет SHA-256 и принимает только тот же самый файл.
 */
let lastShare = 0;

async function shareMissing(cache: Cache) {
	// Синхронизация бывает часто, а потерянный файл – редкость: спрашиваем раз в 10 минут.
	if (Date.now() - lastShare < 10 * 60_000) return;
	lastShare = Date.now();
	const saved = new Map<number, Request>();
	for (const req of await cache.keys()) {
		const id = Number(/\/api\/files\/(\d+)/.exec(new URL(req.url).pathname)?.[1]);
		if (id > 0) saved.set(id, req);
	}
	if (!saved.size) return;
	try {
		const { missing } = await request<{ missing: number[] }>('/api/files/missing', {
			body: { ids: [...saved.keys()].slice(0, 1000) }
		});
		for (const id of missing) {
			const res = await cache.match(saved.get(id)!);
			if (!res?.ok) continue;
			await request(`/api/files/${id}/content`, { method: 'PUT', raw: await res.blob() });
		}
	} catch {
		/* сети нет или файл не тот – попробуем при следующей синхронизации */
	}
}

export async function isSaved(fileId: number): Promise<boolean> {
	if (!available()) return false;
	const cache = await caches.open(FILES_CACHE);
	return !!(await cache.match(`/api/files/${fileId}`, { ignoreSearch: true }));
}

export async function saveFile(fileId: number): Promise<boolean> {
	if (!available()) return false;
	const res = await fetch(`/api/files/${fileId}`, { credentials: 'same-origin' });
	if (!res.ok) return false;
	await (await caches.open(FILES_CACHE)).put(`/api/files/${fileId}`, res);
	return true;
}

/** Сколько файлов сохранено и сколько места занимают данные сайта на устройстве. */
export async function usage(): Promise<{ files: number; bytes: number | null }> {
	const files = available() ? (await (await caches.open(FILES_CACHE)).keys()).length : 0;
	const est = await navigator.storage?.estimate?.().catch(() => null);
	return { files, bytes: est?.usage ?? null };
}

export async function forgetFiles() {
	if (available()) await caches.delete(FILES_CACHE);
}

/** Просим браузер не удалять данные при нехватке места (установленным приложениям он обычно разрешает). */
export function requestPersistence() {
	navigator.storage?.persist?.().catch(() => {});
}
