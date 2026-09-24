import { describe, expect, it } from 'vitest';
import type { Homework, Material, Me, NewsItem, Subject } from '$lib/types';
import { NotFound, highlight, materialListing, resolve, stem, type Snapshot } from './local';

const NOW = new Date(2026, 8, 23, 12, 0).getTime();
const DAY = 86_400_000;
const g1 = { id: 1, name: 'БИН2509' };
const g2 = { id: 2, name: 'БИН2510' };
const person = { id: 1, displayName: 'Ким Олег', avatar: null, deleted: false };
const can = { edit: false, delete: false, hide: false };

function hw(id: number, dueIn: number, extra: Partial<Homework> = {}): Homework {
	return {
		id,
		title: `Задание ${id}`,
		bodyMd: '',
		bodyHtml: '',
		dueAt: NOW + dueIn,
		difficulty: null,
		kind: 'homework',
		place: '',
		done: false,
		hidden: false,
		createdAt: NOW - DAY,
		updatedAt: NOW - DAY,
		author: person,
		subject: { id: 10, name: 'Физика', color: '#f00' },
		groups: [g1],
		comments: 0,
		attachments: [],
		can,
		...extra
	};
}

function news(id: number, extra: Partial<NewsItem> = {}): NewsItem {
	return {
		id,
		title: `Новость ${id}`,
		bodyMd: '',
		bodyHtml: '',
		pinned: false,
		urgent: false,
		hidden: false,
		createdAt: NOW - (100 - id) * 1000,
		updatedAt: NOW,
		author: person,
		subject: null,
		groups: [g1],
		comments: 0,
		can,
		...extra
	};
}

function material(id: number, folderId: number | null, extra: Partial<Material> = {}): Material {
	return {
		id,
		subjectId: 10,
		subjectName: 'Физика',
		subjectColor: '#f00',
		folderId,
		kind: 'file',
		title: `Лекция ${id}`,
		description: '',
		url: null,
		file: { id: 100 + id, name: `lecture-${id}.pdf`, mime: 'application/pdf', size: 1000 },
		author: person,
		status: 'published',
		hidden: false,
		createdAt: NOW - id,
		comments: 0,
		can: { edit: false, delete: false, moderate: false },
		...extra
	};
}

const subject: Subject = {
	id: 10,
	name: 'Физика',
	teacher: 'Сидоров П. П.',
	color: '#f00',
	avatar: null,
	icon: null,
	chatUrl: null,
	archived: false,
	pinned: false,
	groups: [g1],
	can: { edit: false, share: false }
};

const me = {
	user: {
		id: 1,
		username: 'oleg',
		displayName: 'Ким Олег',
		avatar: null,
		instanceRole: null,
		totpEnabled: false
	},
	restriction: null,
	instance: { name: '', mode: 'single', version: 'x', requireStaffTotp: true },
	groups: [
		{
			...g1,
			university: '',
			course: 1,
			avatar: null,
			role: 'student',
			permissions: ['view_group', 'suggest_materials']
		}
	],
	permissions: []
} as unknown as Me;

const snap: Snapshot = {
	me,
	homework: [
		hw(1, 2 * DAY),
		hw(2, 1 * DAY),
		hw(3, -2 * DAY),
		hw(4, -3 * DAY, { done: true }),
		hw(5, 20 * DAY),
		hw(6, DAY, { groups: [g2], title: 'Чужая группа' }),
		hw(7, 3 * DAY, { title: 'Типовой расчёт', bodyMd: 'Решить **задачи** 1–12' })
	],
	news: [
		news(1, { pinned: true }),
		...[2, 3, 4, 5, 6, 7, 8].map((i) => news(i)),
		news(-1, { pending: true })
	],
	materials: [
		material(1, null),
		material(2, 5),
		material(3, 5, { status: 'pending' }),
		material(4, 6)
	],
	folders: [
		{ id: 5, subjectId: 10, parentId: null, name: 'Лекции' },
		{ id: 6, subjectId: 10, parentId: 5, name: 'Осень' }
	],
	subjects: [subject],
	comments: {
		'post:2': [
			{
				id: 1,
				author: person,
				bodyHtml: '<p>Ок</p>',
				createdAt: NOW,
				hidden: false,
				canDelete: false
			}
		]
	},
	members: { 1: [] }
};

describe('офлайн-ответы', () => {
	it('главная: неделя по сроку, просрочка без выполненного, 5 новостей', () => {
		const t = resolve('/api/today', snap, NOW) as ReturnType<typeof import('./local').today>;
		expect(t.upcoming.map((h) => h.id)).toEqual([2, 6, 1, 7]);
		expect(t.overdue.map((h) => h.id)).toEqual([3]);
		expect(t.pinned.map((n) => n.id)).toEqual([1]);
		expect(t.news.map((n) => n.id)).toEqual([-1, 8, 7, 6, 5]);
		const onlyG2 = resolve('/api/today?group=2', snap, NOW) as { upcoming: Homework[] };
		expect(onlyG2.upcoming.map((h) => h.id)).toEqual([6]);
	});

	it('задания: представления как у сервера', () => {
		const all = resolve('/api/homework?view=all&limit=3', snap, NOW) as Homework[];
		expect(all.map((h) => h.id)).toEqual([5, 7, 1]);
		const range = resolve(
			`/api/homework?view=range&from=${NOW}&to=${NOW + 2.5 * DAY}&group=1`,
			snap,
			NOW
		) as Homework[];
		expect(range.map((h) => h.id)).toEqual([2, 1]);
	});

	it('лента новостей листается курсором', () => {
		const p1 = resolve('/api/news?limit=3', snap) as {
			pinned: NewsItem[];
			items: NewsItem[];
			next: number | null;
		};
		expect(p1.pinned.map((n) => n.id)).toEqual([1]);
		expect(p1.items.map((n) => n.id)).toEqual([-1, 8, 7]);
		const p2 = resolve(`/api/news?limit=3&before=${p1.next}`, snap) as typeof p1;
		expect(p2.pinned).toEqual([]);
		expect(p2.items.map((n) => n.id)).toEqual([6, 5, 4]);
	});

	it('материалы: папки со счётчиками, путь и права', () => {
		const root = materialListing(snap, 10, null);
		expect(root.folders).toEqual([{ id: 5, parentId: null, name: 'Лекции', count: 1 }]);
		expect(root.materials.map((m) => m.id)).toEqual([1]);
		expect(root.canUpload).toBe(false);
		expect(root.canSuggest).toBe(true);
		const inner = materialListing(snap, 10, 6);
		expect(inner.path).toEqual([
			{ id: 5, name: 'Лекции' },
			{ id: 6, name: 'Осень' }
		]);
		const lectures = materialListing(snap, 10, 5);
		expect(lectures.materials.map((m) => m.id)).toEqual([3, 2]);
		expect(() => materialListing(snap, 10, 99)).toThrow(NotFound);
	});

	it('поиск без сети: формы слов, «ё», подсветка', () => {
		expect(stem('задачи')).toBe('задач');
		const r = resolve('/api/search?q=расчет', snap) as {
			items: { id: number; title: { text: string; hit: boolean }[] }[];
		};
		expect(r.items.map((i) => i.id)).toEqual([7]);
		expect(r.items[0].title).toEqual([
			{ text: 'Типовой ', hit: false },
			{ text: 'расчёт', hit: true }
		]);
		const byBody = resolve('/api/search?q=задача&kind=homework', snap) as {
			items: { id: number }[];
		};
		expect(byBody.items.map((i) => i.id)).toEqual([7]);
		expect(highlight('Лекция', ['лекц'])).toEqual([
			{ text: 'Лекц', hit: true },
			{ text: 'ия', hit: false }
		]);
	});

	it('детали, комментарии и неизвестное', () => {
		expect((resolve('/api/homework/7', snap) as Homework).title).toBe('Типовой расчёт');
		expect((resolve('/api/news/-1', snap) as NewsItem).pending).toBe(true);
		expect(resolve('/api/news/2/comments', snap)).toHaveLength(1);
		expect(resolve('/api/news/3/comments', snap)).toEqual([]);
		expect(() => resolve('/api/homework/404', snap)).toThrow(NotFound);
		expect(resolve('/api/notifications', snap)).toBeUndefined();
		expect(resolve('/api/me', snap)).toBe(me);
	});
});
