import type { FileInfo } from '$lib/types';
import type { Snapshot } from './local';

/**
 * Файлы материалов и вложений на устройстве. Service worker отдаёт их из этого кеша, когда нет
 * сети, и сам сохраняет всё, что человек открывал. Заранее скачиваем по выбранному правилу.
 */

export const FILES_CACHE = 'files-v1';
const POLICY = 'gb-files-policy';
const SMALL = 20 * 1024 * 1024;

/** all — все файлы, small — до 20 МБ, opened — только открытые. */
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
	return [...out.values()];
}

let prefetching = false;

export async function prefetchFiles(s: Snapshot) {
	const policy = filesPolicy();
	if (!available() || policy === 'opened' || prefetching || !navigator.onLine) return;
	prefetching = true;
	try {
		const cache = await caches.open(FILES_CACHE);
		for (const f of wanted(s)) {
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
	} finally {
		prefetching = false;
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
