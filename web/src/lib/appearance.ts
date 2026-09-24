import { get } from '$lib/api';

/** Встроенные фоны страниц входа (рисунок — CSS-классы .login-bg-* в app.css). */
export const PRESETS = [
	{ key: 'aurora', label: 'Сияние' },
	{ key: 'paper', label: 'Бумага' },
	{ key: 'notebook', label: 'Тетрадь в клетку' },
	{ key: 'night', label: 'Ночь' }
] as const;

export const DEFAULT_BACKGROUND = 'preset:aurora';
const KEY = 'gb-login-bg';

export interface Background {
	/** CSS-класс встроенного рисунка или null. */
	preset: string | null;
	/** Адрес своей картинки администратора или null. */
	image: string | null;
}

/** preset:aurora | image:<id> | none → что рисовать. */
export function parseBackground(value: string | null | undefined): Background {
	const v = value ?? DEFAULT_BACKGROUND;
	if (v === 'none') return { preset: null, image: null };
	if (v.startsWith('image:') && /^[0-9a-f]{20}$/.test(v.slice(6)))
		return { preset: null, image: `/api/appearance/background/${v.slice(6)}.webp` };
	const p = v.startsWith('preset:') ? v.slice(7) : '';
	return { preset: PRESETS.some((x) => x.key === p) ? p : 'aurora', image: null };
}

/** Последний известный фон — чтобы страница входа сразу открывалась с ним, без мигания. */
export function cachedBackground(): string {
	try {
		return localStorage.getItem(KEY) ?? DEFAULT_BACKGROUND;
	} catch {
		return DEFAULT_BACKGROUND;
	}
}

export async function loadBackground(): Promise<string> {
	const r = await get<{ loginBackground: string }>('/api/appearance', { anonymous: true });
	rememberBackground(r.loginBackground);
	return r.loginBackground;
}

export function rememberBackground(v: string) {
	try {
		localStorage.setItem(KEY, v);
	} catch {
		/* не запоминаем */
	}
}
