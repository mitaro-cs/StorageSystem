/** «Недавнее»: последние 5 открытых предметов и материалов. Только в этом браузере. */
export interface RecentItem {
	type: 'subject' | 'material';
	id: number;
	title: string;
	color?: string;
}

const KEY = 'gb-recent';
const MAX = 5;

export function recent(): RecentItem[] {
	try {
		const v = JSON.parse(localStorage.getItem(KEY) ?? '[]');
		return Array.isArray(v) ? v.slice(0, MAX) : [];
	} catch {
		return [];
	}
}

export function track(item: RecentItem) {
	try {
		const list = recent().filter((r) => !(r.type === item.type && r.id === item.id));
		localStorage.setItem(KEY, JSON.stringify([item, ...list].slice(0, MAX)));
	} catch {
		/* приватный режим — не запоминаем */
	}
}

export function clearRecent() {
	try {
		localStorage.removeItem(KEY);
	} catch {
		/* нечего чистить */
	}
}
