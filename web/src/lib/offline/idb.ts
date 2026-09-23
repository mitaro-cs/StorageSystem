/**
 * Локальная копия данных на устройстве (IndexedDB). Отдельная база на пользователя: при выходе
 * она удаляется целиком. Никаких сторонних библиотек — только небольшая обёртка над API браузера.
 */

export type StoreName =
	| 'news'
	| 'homework'
	| 'materials'
	| 'folders'
	| 'subjects'
	| 'comments'
	| 'members'
	| 'meta'
	| 'outbox'
	| 'blobs';

const VERSION = 1;
const KEYS: Record<StoreName, { keyPath?: string; autoIncrement?: boolean }> = {
	news: { keyPath: 'id' },
	homework: { keyPath: 'id' },
	materials: { keyPath: 'id' },
	folders: { keyPath: 'id' },
	subjects: { keyPath: 'id' },
	comments: { keyPath: 'key' },
	members: { keyPath: 'groupId' },
	meta: {},
	outbox: { keyPath: 'seq', autoIncrement: true },
	blobs: { keyPath: 'id' }
};

const LAST_USER = 'gb-last-user';
let opened: { userId: number; db: Promise<IDBDatabase> } | null = null;

export const supported = () => typeof indexedDB !== 'undefined';

function dbName(userId: number) {
	return `groupbase-${userId}`;
}

function req<T>(r: IDBRequest<T>): Promise<T> {
	return new Promise((resolve, reject) => {
		r.onsuccess = () => resolve(r.result);
		r.onerror = () => reject(r.error);
	});
}

export function openDb(userId: number): Promise<IDBDatabase> {
	if (opened?.userId === userId) return opened.db;
	try {
		localStorage.setItem(LAST_USER, String(userId));
	} catch {
		/* без запоминания — офлайн-вход не сработает, остальное да */
	}
	const r = indexedDB.open(dbName(userId), VERSION);
	r.onupgradeneeded = () => {
		for (const [name, opts] of Object.entries(KEYS)) {
			if (!r.result.objectStoreNames.contains(name)) r.result.createObjectStore(name, opts);
		}
	};
	opened = { userId, db: req(r) };
	return opened.db;
}

/** База последнего вошедшего пользователя — для запуска без сети, когда сервер не ответил. */
export function lastUser(): number | null {
	try {
		const v = localStorage.getItem(LAST_USER);
		return v ? Number(v) : null;
	} catch {
		return null;
	}
}

export async function currentDb(): Promise<IDBDatabase | null> {
	if (opened) return opened.db;
	const u = lastUser();
	return u && supported() ? openDb(u) : null;
}

export async function all<T>(store: StoreName): Promise<T[]> {
	const db = await currentDb();
	if (!db) return [];
	return req(db.transaction(store).objectStore(store).getAll()) as Promise<T[]>;
}

export async function one<T>(store: StoreName, key: IDBValidKey): Promise<T | undefined> {
	const db = await currentDb();
	if (!db) return undefined;
	return req(db.transaction(store).objectStore(store).get(key)) as Promise<T | undefined>;
}

/** Несколько изменений атомарно: либо все, либо ни одного. */
export async function write(
	stores: StoreName[],
	fn: (s: (name: StoreName) => IDBObjectStore) => void
): Promise<void> {
	const db = await currentDb();
	if (!db) return;
	const t = db.transaction(stores, 'readwrite');
	fn((name) => t.objectStore(name));
	await new Promise<void>((resolve, reject) => {
		t.oncomplete = () => resolve();
		t.onerror = () => reject(t.error);
		t.onabort = () => reject(t.error);
	});
}

export const getMeta = <T>(key: string) => one<T>('meta', key);

export const setMeta = (key: string, value: unknown) =>
	write(['meta'], (s) => s('meta').put(value, key));

/** Выход из аккаунта: копия на устройстве удаляется. */
export async function dropDb(userId: number) {
	if (opened?.userId === userId) {
		(await opened.db).close();
		opened = null;
	}
	try {
		localStorage.removeItem(LAST_USER);
	} catch {
		/* ничего */
	}
	await req(indexedDB.deleteDatabase(dbName(userId))).catch(() => {});
}
