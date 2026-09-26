import type {
	Comment,
	Homework,
	Material,
	Me,
	Member,
	NewsItem,
	SearchHit,
	SearchKind,
	SearchResult,
	SearchSegment,
	Subject
} from '$lib/types';

/**
 * Ответы API из копии на устройстве — когда сети нет. Те же формы и те же правила отбора, что
 * у сервера (HomeworkService.View, NewsService.feed, MaterialService.list и т.д.). Всё, что
 * лежит в копии, пользователю уже разрешено: сервер отбирает это при синхронизации.
 */

export interface FolderRef {
	id: number;
	subjectId: number;
	parentId: number | null;
	name: string;
}

export interface Snapshot {
	me: Me | null;
	news: NewsItem[];
	homework: Homework[];
	materials: Material[];
	folders: FolderRef[];
	subjects: Subject[];
	/** Ключ — «post:12», «homework:5», «material:7». */
	comments: Record<string, Comment[]>;
	members: Record<number, Member[]>;
}

export class NotFound extends Error {}

const DAY = 24 * 60 * 60 * 1000;

function startOfDay(ms: number): number {
	const d = new Date(ms);
	d.setHours(0, 0, 0, 0);
	return d.getTime();
}

const byDue = (a: Homework, b: Homework) => a.dueAt - b.dueAt;
/** Новые сверху; неотправленные (отрицательный id) — самыми первыми. */
const newest = (a: { id: number; createdAt: number }, b: { id: number; createdAt: number }) =>
	Number(b.id < 0) - Number(a.id < 0) || b.createdAt - a.createdAt || b.id - a.id;

function inScope(groups: { id: number }[], scope: number[] | null): boolean {
	return scope === null || groups.some((g) => scope.includes(g.id));
}

function num(q: URLSearchParams, key: string): number | null {
	const v = q.get(key);
	return v === null || v === '' ? null : Number(v);
}

function comments(s: Snapshot, type: string, id: number): Comment[] {
	return s.comments[`${type}:${id}`] ?? [];
}

function find<T extends { id: number }>(list: T[], id: number): T {
	const x = list.find((i) => i.id === id);
	if (!x) throw new NotFound();
	return x;
}

/** Предметы, скрытые у себя («не мой предмет»): в общих списках их нет — как на сервере. */
function notMine(s: Snapshot): Set<number> {
	return new Set(s.subjects.filter((x) => x.mine === false).map((x) => x.id));
}

export function homeworkList(s: Snapshot, q: URLSearchParams, now: number): Homework[] {
	const group = num(q, 'group');
	const subject = num(q, 'subject');
	const scope = group === null ? null : [group];
	const hidden = subject === null ? notMine(s) : new Set<number>();
	const list = s.homework.filter(
		(h) =>
			inScope(h.groups, scope) &&
			(subject === null || h.subject.id === subject) &&
			!hidden.has(h.subject.id)
	);
	const sod = startOfDay(now);
	switch (q.get('view') ?? 'week') {
		case 'overdue':
			return list
				.filter((h) => h.dueAt < now && h.dueAt >= now - 60 * DAY && !h.done)
				.sort((a, b) => b.dueAt - a.dueAt);
		case 'range': {
			const from = num(q, 'from') ?? sod;
			const to = num(q, 'to') ?? sod + 31 * DAY;
			return list.filter((h) => h.dueAt >= from && h.dueAt < to).sort(byDue);
		}
		case 'all': {
			const limit = Math.min(num(q, 'limit') ?? 100, 200);
			return list.sort((a, b) => b.dueAt - a.dueAt).slice(0, limit);
		}
		case 'exams':
			return list
				.filter(
					(h) =>
						(h.kind === 'credit' || h.kind === 'exam') &&
						h.dueAt >= sod - 45 * DAY &&
						h.dueAt < sod + 180 * DAY
				)
				.sort(byDue)
				.slice(0, 100);
		default:
			return list.filter((h) => h.dueAt >= sod && h.dueAt < sod + 8 * DAY).sort(byDue);
	}
}

export function newsFeed(s: Snapshot, q: URLSearchParams) {
	const group = num(q, 'group');
	const subject = num(q, 'subject');
	const before = num(q, 'before');
	const limit = Math.min(Math.max(num(q, 'limit') ?? 20, 1), 50);
	const scope = group === null ? null : [group];
	const hidden = subject === null ? notMine(s) : new Set<number>();
	const list = s.news
		.filter(
			(n) =>
				inScope(n.groups, scope) &&
				(subject === null || n.subject?.id === subject) &&
				!(n.subject && hidden.has(n.subject.id))
		)
		.sort(newest);
	const pinned = before === null ? list.filter((n) => n.pinned).slice(0, 10) : [];
	const rest = list.filter((n) => !n.pinned && (before === null || (n.id > 0 && n.id < before)));
	const items = rest.slice(0, limit);
	return { pinned, items, next: rest.length > limit ? (items.at(-1)?.id ?? null) : null };
}

export function today(s: Snapshot, q: URLSearchParams, now: number) {
	const feed = newsFeed(s, new URLSearchParams({ ...Object.fromEntries(q), limit: '5' }));
	return {
		upcoming: homeworkList(s, new URLSearchParams({ ...Object.fromEntries(q), view: 'week' }), now),
		overdue: homeworkList(
			s,
			new URLSearchParams({ ...Object.fromEntries(q), view: 'overdue' }),
			now
		),
		pinned: feed.pinned,
		news: feed.items,
		exams: homeworkList(s, new URLSearchParams({ ...Object.fromEntries(q), view: 'exams' }), now)
	};
}

function can(s: Snapshot, perm: string, groupIds: number[]): boolean {
	const me = s.me;
	if (!me) return false;
	if (me.user.instanceRole === 'admin') return true;
	return me.groups.some(
		(g) => groupIds.includes(g.id) && (g.permissions as string[]).includes(perm)
	);
}

export function materialListing(s: Snapshot, subjectId: number, folder: number | null) {
	const subject = find(s.subjects, subjectId);
	if (folder !== null && !s.folders.some((f) => f.id === folder && f.subjectId === subjectId))
		throw new NotFound();
	const path: { id: number; name: string }[] = [];
	for (let cur = folder, guard = 0; cur !== null && guard < 32; guard++) {
		const f = s.folders.find((x) => x.id === cur);
		if (!f) break;
		path.unshift({ id: f.id, name: f.name });
		cur = f.parentId;
	}
	const published = (m: Material) => m.status === 'published' && !m.hidden;
	const folders = s.folders
		.filter((f) => f.subjectId === subjectId && f.parentId === folder)
		.sort((a, b) => a.name.localeCompare(b.name, 'ru'))
		.map((f) => ({
			id: f.id,
			parentId: f.parentId,
			name: f.name,
			count: s.materials.filter((m) => m.folderId === f.id && published(m)).length
		}));
	const materials = s.materials
		.filter((m) => m.subjectId === subjectId && m.folderId === folder)
		.sort(
			(a, b) => Number(b.status === 'pending') - Number(a.status === 'pending') || newest(a, b)
		);
	const groupIds = subject.groups.map((g) => g.id);
	return {
		path,
		folders,
		materials,
		canUpload: can(s, 'upload_materials', groupIds),
		canSuggest: can(s, 'suggest_materials', groupIds)
	};
}

// --- поиск без сети: те же правила слов, что у сервера (SearchQuery) ---

const ENDINGS = [
	'иями',
	'ями',
	'ами',
	'ого',
	'его',
	'ому',
	'ему',
	'ыми',
	'ими',
	'ией',
	'иям',
	'иях',
	'ать',
	'ять',
	'ить',
	'еть',
	'ешь',
	'ете',
	'ует',
	'ют',
	'ут',
	'ая',
	'яя',
	'ое',
	'ее',
	'ые',
	'ие',
	'ый',
	'ий',
	'ой',
	'ей',
	'ам',
	'ям',
	'ах',
	'ях',
	'ом',
	'ем',
	'ов',
	'ев',
	'ия',
	'ью',
	'ы',
	'и',
	'а',
	'я',
	'о',
	'е',
	'у',
	'ю',
	'ь',
	'й'
];

const fold = (t: string) => t.toLowerCase().replace(/ё/g, 'е');

export function stem(word: string): string {
	if (word.length < 4 || !/[а-я]/.test(word)) return word;
	for (const e of ENDINGS) {
		const rest = word.length - e.length;
		if (word.endsWith(e) && rest >= (e.length === 1 ? 3 : 4)) return word.slice(0, rest);
	}
	return word;
}

function terms(q: string): string[] {
	return (fold(q).match(/[\p{L}\p{N}]+/gu) ?? []).slice(0, 8).map(stem);
}

/** Подсветка: отрезки текста, где найдено любое из слов запроса. */
export function highlight(text: string, stems: string[]): SearchSegment[] {
	const f = fold(text);
	const marks = new Array<boolean>(text.length).fill(false);
	for (const st of stems) {
		for (let i = f.indexOf(st); i >= 0; i = f.indexOf(st, i + 1)) {
			for (let j = i; j < i + st.length; j++) marks[j] = true;
		}
	}
	const out: SearchSegment[] = [];
	for (let i = 0; i < text.length;) {
		let j = i;
		while (j < text.length && marks[j] === marks[i]) j++;
		out.push({ text: text.slice(i, j), hit: marks[i] });
		i = j;
	}
	return out;
}

function snippet(body: string, stems: string[]): SearchSegment[] {
	const plain = body
		.replace(/[*_`#>|~]+/g, '')
		.replace(/\s+/g, ' ')
		.trim();
	const f = fold(plain);
	const at = Math.min(...stems.map((st) => f.indexOf(st)).filter((i) => i >= 0), Infinity);
	if (!Number.isFinite(at)) return plain ? highlight(plain.slice(0, 120), stems) : [];
	const start = Math.max(0, at - 50);
	const piece =
		(start > 0 ? '…' : '') +
		plain.slice(start, start + 140) +
		(start + 140 < plain.length ? '…' : '');
	return highlight(piece, stems);
}

export function search(s: Snapshot, q: URLSearchParams): SearchResult {
	const query = (q.get('q') ?? '').trim();
	const stems = terms(query);
	const kind = q.get('kind') as SearchKind | null;
	const group = num(q, 'group');
	const scope = group === null ? null : [group];
	const matches = (...texts: string[]) => {
		const f = fold(texts.join(' '));
		return stems.every((st) => f.includes(st));
	};
	const found: Record<SearchKind, SearchHit[]> = {
		homework: [],
		news: [],
		material: [],
		subject: []
	};
	if (stems.length) {
		for (const h of s.homework)
			if (inScope(h.groups, scope) && matches(h.title, h.bodyMd))
				found.homework.push({
					kind: 'homework',
					id: h.id,
					title: highlight(h.title, stems),
					snippet: snippet(h.bodyMd, stems),
					subject: h.subject,
					date: h.dueAt,
					url: `/homework/${h.id}`,
					hidden: h.hidden
				});
		for (const n of s.news)
			if (inScope(n.groups, scope) && matches(n.title, n.bodyMd))
				found.news.push({
					kind: 'news',
					id: n.id,
					title: highlight(n.title, stems),
					snippet: snippet(n.bodyMd, stems),
					subject: n.subject,
					date: n.createdAt,
					url: `/news/${n.id}`,
					hidden: n.hidden
				});
		for (const m of s.materials)
			if (matches(m.title, m.description, m.url ?? '', m.file?.name ?? ''))
				found.material.push({
					kind: 'material',
					id: m.id,
					title: highlight(m.title, stems),
					snippet: snippet(`${m.description} ${m.file?.name ?? ''}`, stems),
					subject: { id: m.subjectId, name: m.subjectName, color: m.subjectColor },
					date: m.createdAt,
					url: `/materials/${m.id}`,
					hidden: m.hidden || m.status === 'pending'
				});
		for (const sub of s.subjects)
			if (inScope(sub.groups, scope) && matches(sub.name, sub.teacher))
				found.subject.push({
					kind: 'subject',
					id: sub.id,
					title: highlight(sub.name, stems),
					snippet: highlight(sub.teacher, stems),
					subject: { id: sub.id, name: sub.name, color: sub.color },
					date: null,
					url: `/subjects/${sub.id}`,
					hidden: sub.archived
				});
	}
	const kinds: SearchKind[] = kind ? [kind] : ['homework', 'news', 'material', 'subject'];
	const counts: Partial<Record<SearchKind, number>> = {};
	const items: SearchHit[] = [];
	for (const k of kinds) {
		const list = found[k].slice(0, 20);
		counts[k] = list.length;
		items.push(...list);
	}
	return { query, items, counts };
}

/** Ответ на GET-запрос из копии; undefined — такой запрос без сети не работает. */
export function resolve(path: string, s: Snapshot, now = Date.now()): unknown {
	const url = new URL(path, 'http://local');
	const p = url.pathname;
	const q = url.searchParams;
	let m: RegExpMatchArray | null;
	if (p === '/api/me') return s.me ?? undefined;
	if (p === '/api/today') return today(s, q, now);
	if (p === '/api/news') return newsFeed(s, q);
	if ((m = p.match(/^\/api\/news\/(-?\d+)$/))) return find(s.news, Number(m[1]));
	if ((m = p.match(/^\/api\/news\/(-?\d+)\/comments$/))) return comments(s, 'post', Number(m[1]));
	if (p === '/api/homework') return homeworkList(s, q, now);
	if ((m = p.match(/^\/api\/homework\/(-?\d+)$/))) return find(s.homework, Number(m[1]));
	if ((m = p.match(/^\/api\/homework\/(-?\d+)\/comments$/)))
		return comments(s, 'homework', Number(m[1]));
	if (p === '/api/subjects') {
		const group = num(q, 'group');
		return s.subjects.filter((x) => group === null || x.groups.some((g) => g.id === group));
	}
	if ((m = p.match(/^\/api\/subjects\/(\d+)$/))) return find(s.subjects, Number(m[1]));
	if ((m = p.match(/^\/api\/subjects\/(\d+)\/materials$/)))
		return materialListing(s, Number(m[1]), num(q, 'folder'));
	if (p === '/api/materials/recent') {
		const group = num(q, 'group');
		const subjectIds = new Set(
			s.subjects
				.filter((x) => x.mine !== false && (group === null || x.groups.some((g) => g.id === group)))
				.map((x) => x.id)
		);
		return s.materials
			.filter((x) => x.status === 'published' && !x.hidden && subjectIds.has(x.subjectId))
			.sort(newest)
			.slice(0, Math.min(num(q, 'limit') ?? 20, 100));
	}
	if (p === '/api/materials/pending') return s.materials.filter((x) => x.status === 'pending');
	if ((m = p.match(/^\/api\/materials\/(-?\d+)$/))) return find(s.materials, Number(m[1]));
	if ((m = p.match(/^\/api\/materials\/(-?\d+)\/comments$/)))
		return comments(s, 'material', Number(m[1]));
	if ((m = p.match(/^\/api\/groups\/(\d+)\/members$/))) {
		const list = s.members[Number(m[1])];
		if (!list) throw new NotFound();
		return list;
	}
	if (p === '/api/search') return search(s, q);
	return undefined;
}
