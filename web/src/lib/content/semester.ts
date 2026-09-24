/**
 * Мастер «Новый семестр»: разбор списка новых предметов и цвета для них. Сами изменения — обычные
 * запросы: архив старых предметов, создание новых, даты сессии.
 */

export interface NewSubject {
	name: string;
	teacher: string;
	/** Такой предмет уже есть среди оставленных — второй раз не создаём. */
	duplicate: boolean;
}

export const PALETTE = [
	'#3446d4',
	'#0e9f6e',
	'#d97706',
	'#dc2626',
	'#7c3aed',
	'#0891b2',
	'#db2777',
	'#65a30d',
	'#6b7280'
];

const norm = (s: string) => s.toLowerCase().replaceAll('ё', 'е').replace(/\s+/g, ' ').trim();

/**
 * «Физика — Иванов И. И.» → название и преподаватель. Разделитель — тире, дефис с пробелами
 * или «;». Пустые строки и маркеры списков («1.», «-», «•») пропускаются.
 */
export function parseSubjects(text: string, existing: string[] = []): NewSubject[] {
	const have = new Set(existing.map(norm));
	const seen = new Set<string>();
	const out: NewSubject[] = [];
	for (const raw of text.split(/\r?\n/)) {
		const line = raw.replace(/^\s*(?:\d+[.)]|[-•*])\s+/, '').trim();
		if (!line) continue;
		const m = line.match(/^(.*?)\s+[—–-]\s+(.*)$/) ?? line.match(/^(.*?)\s*;\s*(.*)$/);
		const name = (m ? m[1] : line).trim().slice(0, 80);
		const teacher = (m ? m[2] : '').trim().slice(0, 120);
		if (!name) continue;
		const key = norm(name);
		if (seen.has(key)) continue;
		seen.add(key);
		out.push({ name, teacher, duplicate: have.has(key) });
	}
	return out;
}

/** Цвета по кругу, сначала — те, что ещё не заняты оставшимися предметами. */
export function pickColors(count: number, taken: string[]): string[] {
	const used = new Set(taken.map((c) => c.toLowerCase()));
	const order = [...PALETTE.filter((c) => !used.has(c)), ...PALETTE.filter((c) => used.has(c))];
	return Array.from({ length: count }, (_, i) => order[i % order.length]);
}
