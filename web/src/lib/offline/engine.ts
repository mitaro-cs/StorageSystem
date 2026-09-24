import { ApiError, request } from '$lib/api';
import { toast } from '$lib/toasts.svelte';
import type {
	Comment,
	FileInfo,
	Homework,
	Material,
	Me,
	Member,
	NewsItem,
	Person,
	Subject
} from '$lib/types';
import { uploadFile } from '$lib/upload';
import { all, dropDb, getMeta, one, openDb, setMeta, supported, write, lastUser } from './idb';
import type { StoreName } from './idb';
import { NotFound, resolve, type FolderRef, type Snapshot } from './local';
import { forgetFiles, prefetchFiles, requestPersistence } from './files';
import { offline } from './state.svelte';

/**
 * Офлайн-режим: копия данных группы на устройстве, синхронизация с сервером и очередь действий,
 * сделанных без сети. Сервер остаётся главным: копия обновляется по его журналу изменений
 * (/api/sync), а действия из очереди отправляются по порядку, когда появляется сеть.
 */
export { offline };

const DAY = 24 * 60 * 60 * 1000;
const EVERY = 5 * 60 * 1000;

interface Delta<T> {
	upsert: T[];
	delete: number[];
}

interface SyncResult {
	cursor: number;
	full: boolean;
	subjects: Subject[];
	members: Record<string, Member[]>;
	news: Delta<NewsItem>;
	homework: Delta<Homework>;
	materials: Delta<Material>;
	folders: Delta<FolderRef>;
	comments: { type: string; id: number; items: Comment[] }[];
}

interface Op {
	seq?: number;
	method: string;
	path: string;
	body?: Record<string, unknown>;
	createdAt: number;
	label: string;
	/** Временный id созданного объекта или файла (отрицательный). */
	temp?: number;
}

let userId: number | null = null;

/**
 * Обычная копия данных: состояние Svelte — это прокси, а IndexedDB умеет сохранять только
 * «простые» объекты.
 */
const plain = <T>(x: T): T => JSON.parse(JSON.stringify(x)) as T;
let mirror: Snapshot | null = null;
let timer: ReturnType<typeof setInterval> | undefined;

export const enabled = () => supported() && (offline.ready || lastUser() !== null);

// ---------- копия на устройстве ----------

export async function snapshot(): Promise<Snapshot> {
	if (mirror) return mirror;
	const [me, news, homework, materials, folders, subjects, commentRows, memberRows] =
		await Promise.all([
			getMeta<Me>('me'),
			all<NewsItem>('news'),
			all<Homework>('homework'),
			all<Material>('materials'),
			all<FolderRef>('folders'),
			all<Subject>('subjects'),
			all<{ key: string; items: Comment[] }>('comments'),
			all<{ groupId: number; items: Member[] }>('members')
		]);
	mirror = {
		me: me ?? null,
		news,
		homework,
		materials,
		folders,
		subjects,
		comments: Object.fromEntries(commentRows.map((r) => [r.key, r.items])),
		members: Object.fromEntries(memberRows.map((r) => [r.groupId, r.items]))
	};
	offline.counts = { news: news.length, homework: homework.length, materials: materials.length };
	return mirror;
}

function changed() {
	mirror = null;
	offline.version++;
}

/** Ответ на GET из копии; undefined — без сети этого нет. */
export async function resolveLocal(path: string): Promise<unknown> {
	try {
		return resolve(path, await snapshot());
	} catch (e) {
		if (e instanceof NotFound)
			throw new ApiError(404, 'not_found', 'Этого нет в сохранённых данных на устройстве');
		throw e;
	}
}

// ---------- запуск и выход ----------

export async function initOffline(me: Me) {
	if (!supported()) return;
	if (userId !== me.user.id) {
		userId = me.user.id;
		await openDb(userId);
		mirror = null;
	}
	await setMeta('me', plain(me));
	offline.ready = true;
	offline.lastSync = (await getMeta<number>('lastSync')) ?? 0;
	offline.pending = (await all('outbox')).length;
	requestPersistence();
	if (!timer) {
		timer = setInterval(() => {
			if (document.visibilityState === 'visible') run();
		}, EVERY);
		addEventListener('online', run);
	}
	run();
}

/** Профиль обновился — копия тоже. */
export async function rememberMe(me: Me) {
	if (offline.ready && userId === me.user.id) {
		await setMeta('me', plain(me));
		if (mirror) mirror.me = plain(me);
	}
}

export async function wipeOffline() {
	clearInterval(timer);
	timer = undefined;
	removeEventListener('online', run);
	const u = userId ?? lastUser();
	userId = null;
	mirror = null;
	offline.ready = false;
	offline.pending = 0;
	if (u !== null) await dropDb(u);
	await forgetFiles();
}

async function run() {
	await flushOutbox();
	await syncNow();
}

// ---------- синхронизация ----------

let running: Promise<void> | null = null;

export function syncNow(): Promise<void> {
	running ??= doSync().finally(() => (running = null));
	return running;
}

const CONTENT: StoreName[] = ['news', 'homework', 'materials', 'folders', 'comments'];

async function doSync() {
	if (!userId || !navigator.onLine) return;
	offline.syncing = true;
	try {
		const cursor = (await getMeta<number>('cursor')) ?? 0;
		const lastFull = (await getMeta<number>('lastFull')) ?? 0;
		// Раз в сутки — полный снимок: заодно уходит то, что стало невидимым незаметно.
		const after = Date.now() - lastFull > DAY ? 0 : cursor;
		const r = await request<SyncResult>(`/api/sync?after=${after}`);
		const touched =
			r.full ||
			r.comments.length > 0 ||
			[r.news, r.homework, r.materials, r.folders].some((d) => d.upsert.length || d.delete.length);
		await write([...CONTENT, 'subjects', 'members', 'meta'], (s) => {
			if (r.full) CONTENT.forEach((name) => s(name).clear());
			const apply = <T>(name: StoreName, d: Delta<T>, commentType?: string) => {
				for (const id of d.delete) {
					s(name).delete(id);
					if (commentType) s('comments').delete(`${commentType}:${id}`);
				}
				for (const x of d.upsert) s(name).put(x);
			};
			apply('news', r.news, 'post');
			apply('homework', r.homework, 'homework');
			apply('materials', r.materials, 'material');
			apply('folders', r.folders);
			for (const c of r.comments) s('comments').put({ key: `${c.type}:${c.id}`, items: c.items });
			s('subjects').clear();
			r.subjects.forEach((x) => s('subjects').put(x));
			s('members').clear();
			for (const [groupId, items] of Object.entries(r.members))
				s('members').put({ groupId: Number(groupId), items });
			s('meta').put(r.cursor, 'cursor');
			s('meta').put(Date.now(), 'lastSync');
			if (r.full) s('meta').put(Date.now(), 'lastFull');
		});
		offline.lastSync = Date.now();
		if (touched) changed();
		else mirror = null;
		prefetchFiles(await snapshot());
	} catch {
		/* сеть пропала или сервер недоступен — попробуем позже */
	} finally {
		offline.syncing = false;
	}
}

// ---------- действия без сети ----------

const QUEUEABLE: [string, RegExp][] = [
	['PUT', /^\/api\/homework\/-?\d+\/done$/],
	['POST', /^\/api\/(news|homework|materials)\/-?\d+\/comments$/],
	['POST', /^\/api\/news$/],
	['POST', /^\/api\/homework$/],
	['POST', /^\/api\/subjects\/\d+\/materials$/]
];

export function queueable(method: string, path: string): boolean {
	return QUEUEABLE.some(([m, re]) => m === method && re.test(path));
}

async function nextTemp(): Promise<number> {
	const t = ((await getMeta<number>('temp')) ?? 0) - 1;
	await setMeta('temp', t);
	return t;
}

function mePerson(me: Me | null): Person {
	return {
		id: me?.user.id ?? 0,
		displayName: me?.user.displayName ?? '',
		avatar: me?.user.avatar ?? null,
		deleted: false
	};
}

function escapeHtml(t: string): string {
	return t.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`).replace(/\n/g, '<br>');
}

function groupRefs(me: Me | null, ids: unknown): { id: number; name: string }[] {
	const list = Array.isArray(ids) ? (ids as number[]) : [];
	return list.map((id) => ({ id, name: me?.groups.find((g) => g.id === id)?.name ?? '' }));
}

/**
 * Действие без сети: записывается в очередь, а на устройстве сразу видно результат
 * (с пометкой «ожидает отправки»). Возвращает то, что вернул бы сервер.
 */
export async function enqueue(method: string, path: string, body?: unknown): Promise<unknown> {
	const s = await snapshot();
	const b = plain((body ?? {}) as Record<string, unknown>);
	const now = Date.now();
	const me = s.me;
	const can = { edit: false, delete: false, hide: false };
	let m: RegExpMatchArray | null;
	let label = 'Действие';
	let temp: number | undefined;
	let result: unknown = { status: 'ok' };
	const puts: [StoreName, unknown][] = [];

	if ((m = path.match(/^\/api\/homework\/(-?\d+)\/done$/))) {
		const h = s.homework.find((x) => x.id === Number(m![1]));
		if (h) puts.push(['homework', { ...h, done: !!b.value }]);
		label = `${b.value ? 'Отметка «сделано»' : 'Снятие отметки'}: ${h?.title ?? ''}`;
	} else if ((m = path.match(/^\/api\/(news|homework|materials)\/(-?\d+)\/comments$/))) {
		temp = await nextTemp();
		const type = m[1] === 'news' ? 'post' : m[1] === 'homework' ? 'homework' : 'material';
		const store: StoreName =
			m[1] === 'news' ? 'news' : m[1] === 'homework' ? 'homework' : 'materials';
		const id = Number(m[2]);
		const comment: Comment = {
			id: temp,
			author: mePerson(me),
			bodyHtml: `<p>${escapeHtml(String(b.body ?? ''))}</p>`,
			createdAt: now,
			hidden: false,
			canDelete: false,
			pending: true
		};
		const key = `${type}:${id}`;
		puts.push(['comments', { key, items: [...(s.comments[key] ?? []), comment] }]);
		const parent = (s[store] as { id: number; comments: number }[]).find((x) => x.id === id);
		if (parent) puts.push([store, { ...parent, comments: parent.comments + 1 }]);
		label = 'Комментарий';
		result = comment;
	} else if (path === '/api/news') {
		temp = await nextTemp();
		const subject = s.subjects.find((x) => x.id === b.subjectId);
		const item: NewsItem = {
			id: temp,
			title: String(b.title ?? ''),
			bodyMd: String(b.body ?? ''),
			bodyHtml: `<p>${escapeHtml(String(b.body ?? ''))}</p>`,
			pinned: !!b.pinned,
			urgent: !!b.urgent,
			hidden: false,
			createdAt: now,
			updatedAt: now,
			author: mePerson(me),
			subject: subject ? { id: subject.id, name: subject.name, color: subject.color } : null,
			groups: groupRefs(me, b.groupIds),
			comments: 0,
			can,
			pending: true
		};
		puts.push(['news', item]);
		label = `Новость «${item.title}»`;
		result = item;
	} else if (path === '/api/homework') {
		temp = await nextTemp();
		const subject = s.subjects.find((x) => x.id === b.subjectId);
		const files = (Array.isArray(b.attachments) ? (b.attachments as number[]) : []).map(
			(id) => stashed.get(id) ?? { id, name: 'файл', mime: '', size: 0 }
		);
		const groups = groupRefs(me, b.groupIds);
		const item: Homework = {
			id: temp,
			title: String(b.title ?? ''),
			bodyMd: String(b.body ?? ''),
			bodyHtml: `<p>${escapeHtml(String(b.body ?? ''))}</p>`,
			dueAt: Number(b.dueAt),
			difficulty: [1, 2, 3].includes(Number(b.difficulty)) ? Number(b.difficulty) : null,
			done: false,
			hidden: false,
			createdAt: now,
			updatedAt: now,
			author: mePerson(me),
			subject: { id: subject?.id ?? 0, name: subject?.name ?? '', color: subject?.color ?? '#888' },
			groups: groups.length ? groups : (subject?.groups ?? []),
			comments: 0,
			attachments: files,
			can,
			pending: true
		};
		puts.push(['homework', item]);
		label = `Задание «${item.title}»`;
		result = item;
	} else if ((m = path.match(/^\/api\/subjects\/(\d+)\/materials$/))) {
		temp = await nextTemp();
		const subject = s.subjects.find((x) => x.id === Number(m![1]));
		const file = typeof b.fileId === 'number' ? (stashed.get(b.fileId) ?? null) : null;
		const item: Material = {
			id: temp,
			subjectId: Number(m[1]),
			subjectName: subject?.name ?? '',
			subjectColor: subject?.color ?? '#888',
			folderId: (b.folderId as number | null) ?? null,
			kind: b.kind === 'link' ? 'link' : 'file',
			title: String(b.title || file?.name || b.url || 'Материал'),
			description: String(b.description ?? ''),
			url: (b.url as string | null) ?? null,
			file,
			author: mePerson(me),
			status: 'published',
			hidden: false,
			createdAt: now,
			comments: 0,
			can: { edit: false, delete: false, moderate: false },
			pending: true
		};
		puts.push(['materials', item]);
		label = `Материал «${item.title}»`;
		result = item;
	}

	const op: Op = { method, path, body: b, createdAt: now, label, temp };
	await write(['outbox', ...new Set(puts.map(([name]) => name))], (st) => {
		st('outbox').add(op);
		for (const [name, value] of puts) st(name).put(plain(value));
	});
	offline.pending++;
	changed();
	return result;
}

/** Файлы, выбранные без сети: хранятся на устройстве до отправки. */
const stashed = new Map<number, FileInfo>();

export async function stashFile(file: File): Promise<FileInfo> {
	const temp = await nextTemp();
	const info: FileInfo = {
		id: temp,
		name: file.name,
		mime: file.type || 'application/octet-stream',
		size: file.size,
		pending: true
	};
	stashed.set(temp, info);
	const op: Op = {
		method: 'UPLOAD',
		path: '/api/files',
		createdAt: Date.now(),
		label: `Файл «${file.name}»`,
		temp
	};
	await write(['outbox', 'blobs'], (st) => {
		st('blobs').put({ id: temp, blob: file, name: file.name, mime: info.mime });
		st('outbox').add(op);
	});
	offline.pending++;
	return info;
}

/** Временные id в адресе и теле заменяются настоящими, полученными при отправке. */
function rewrite(
	op: Op,
	ids: Map<number, number>
): { path: string; body?: Record<string, unknown> } {
	const path = op.path.replace(/\/(-\d+)(?=\/|$)/g, (all, id) =>
		ids.has(Number(id)) ? `/${ids.get(Number(id))}` : all
	);
	if (!op.body) return { path };
	const body = { ...op.body };
	if (Array.isArray(body.attachments))
		body.attachments = (body.attachments as number[]).map((id) => ids.get(id) ?? id);
	if (typeof body.fileId === 'number') body.fileId = ids.get(body.fileId) ?? body.fileId;
	return { path, body };
}

let flushing = false;

export async function flushOutbox(): Promise<void> {
	if (flushing || !userId || !navigator.onLine) return;
	const ops = await all<Op>('outbox');
	if (ops.length === 0) return;
	flushing = true;
	const ids = new Map<number, number>();
	let sent = 0;
	const failed: string[] = [];
	try {
		for (const op of ops) {
			try {
				if (op.method === 'UPLOAD') {
					const b = await one<{ blob: Blob; name: string; mime: string }>('blobs', op.temp!);
					if (b) {
						const info = await uploadFile(new File([b.blob], b.name, { type: b.mime }));
						ids.set(op.temp!, info.id);
					}
					await write(['outbox', 'blobs'], (st) => {
						st('outbox').delete(op.seq!);
						st('blobs').delete(op.temp!);
					});
				} else {
					const { path, body } = rewrite(op, ids);
					const res = await request<{ id?: number }>(path, {
						method: op.method as 'POST' | 'PUT',
						body
					});
					if (op.temp !== undefined && typeof res?.id === 'number') ids.set(op.temp, res.id);
					await write(['outbox'], (st) => st('outbox').delete(op.seq!));
				}
				sent++;
			} catch (e) {
				// Нет сети или сессия кончилась — остальное отправим позже, по порядку.
				if (e instanceof ApiError && (e.status === 0 || e.status === 401)) break;
				failed.push(`${op.label}: ${e instanceof Error ? e.message : 'ошибка'}`);
				await write(['outbox', 'blobs'], (st) => {
					st('outbox').delete(op.seq!);
					if (op.temp !== undefined) st('blobs').delete(op.temp);
				});
			}
		}
	} finally {
		flushing = false;
	}
	offline.pending = (await all('outbox')).length;
	if (sent + failed.length > 0) {
		await dropTemporary();
		changed();
		failed.forEach((f) => toast(`Не отправлено — ${f}`, 'error', 8000));
		if (sent) toast(`Отправлено из очереди: ${sent}`, 'ok');
	}
}

/** Временные копии уходят — настоящие придут со следующей синхронизацией. */
async function dropTemporary() {
	if (offline.pending > 0) return;
	const s = await snapshot();
	await write(['news', 'homework', 'materials', 'comments'], (st) => {
		for (const x of s.news) if (x.id < 0) st('news').delete(x.id);
		for (const x of s.homework) if (x.id < 0) st('homework').delete(x.id);
		for (const x of s.materials) if (x.id < 0) st('materials').delete(x.id);
		for (const [key, items] of Object.entries(s.comments))
			if (items.some((c) => c.id < 0))
				st('comments').put({ key, items: items.filter((c) => c.id > 0) });
	});
	stashed.clear();
	mirror = null;
}

export async function pendingLabels(): Promise<string[]> {
	return (await all<Op>('outbox')).map((o) => o.label);
}
