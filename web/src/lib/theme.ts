export type Theme = 'system' | 'light' | 'dark';
const KEY = 'gb-theme';

export function currentTheme(): Theme {
	try {
		const v = localStorage.getItem(KEY);
		return v === 'light' || v === 'dark' ? v : 'system';
	} catch {
		return 'system';
	}
}

/** Переключает тему с плавным переходом цветов (класс снимается после анимации). */
export function setTheme(theme: Theme) {
	const root = document.documentElement;
	root.classList.add('theme-switching');
	if (theme === 'system') root.removeAttribute('data-theme');
	else root.setAttribute('data-theme', theme);
	try {
		if (theme === 'system') localStorage.removeItem(KEY);
		else localStorage.setItem(KEY, theme);
	} catch {
		/* приватный режим — просто не запоминаем */
	}
	setTimeout(() => root.classList.remove('theme-switching'), 300);
}

/**
 * Цветовая тема (Профиль → Оформление): красит кнопки, активные пункты, переключатели, фон и
 * тёмную карточку. Цвета — в app.css (:root[data-palette]); здесь — список для выбора. light и
 * dark — фон и главный цвет в светлом и тёмном режиме (для образца в списке).
 */
export const PALETTES = [
	{ id: 'classic', label: 'Классика', light: ['#f1f2f4', '#0d0d0f'], dark: ['#000000', '#ffffff'] },
	{ id: 'graphite', label: 'Графит', light: ['#eceef1', '#1d1e22'], dark: ['#111214', '#e8e8ec'] },
	{ id: 'ocean', label: 'Океан', light: ['#eef2f8', '#2f5bea'], dark: ['#04060c', '#7b9cff'] },
	{ id: 'forest', label: 'Лес', light: ['#eef4f0', '#13804f'], dark: ['#030805', '#4fd18b'] },
	{ id: 'sunset', label: 'Закат', light: ['#f8f1ee', '#c9441f'], dark: ['#0b0504', '#ff8a65'] },
	{ id: 'grape', label: 'Виноград', light: ['#f3f0f9', '#7040d8'], dark: ['#07040d', '#b597ff'] }
] as const;

export type Palette = (typeof PALETTES)[number]['id'];
const PALETTE_KEY = 'gb-palette';

export function isPalette(v: unknown): v is Palette {
	return PALETTES.some((p) => p.id === v);
}

export function currentPalette(): Palette {
	try {
		const v = localStorage.getItem(PALETTE_KEY);
		return isPalette(v) ? v : 'classic';
	} catch {
		return 'classic';
	}
}

/** Меняет цветовую тему сразу, с плавным переходом, и запоминает на этом устройстве. */
export function setPalette(palette: Palette) {
	const root = document.documentElement;
	root.classList.add('theme-switching');
	if (palette === 'classic') root.removeAttribute('data-palette');
	else root.setAttribute('data-palette', palette);
	try {
		if (palette === 'classic') localStorage.removeItem(PALETTE_KEY);
		else localStorage.setItem(PALETTE_KEY, palette);
	} catch {
		/* приватный режим — просто не запоминаем */
	}
	setTimeout(() => root.classList.remove('theme-switching'), 300);
}

/**
 * Стиль (Профиль → Оформление): как выглядят карточки и фон. «Обычный» — как было всегда; остальные
 * включает каждый себе сам, на этом устройстве. Правила — в app.css (:root[data-style]) и в
 * карточках новостей и заданий (цвет предмета).
 */
export const STYLES = [
	{ id: 'plain', label: 'Обычный', hint: 'Ровные карточки — как было' },
	{ id: 'depth', label: 'Объём', hint: 'Свет сверху и мягкие тени' },
	{ id: 'glass', label: 'Стекло', hint: 'Полупрозрачные панели на цветном фоне' },
	{ id: 'tint', label: 'Цвет предметов', hint: 'Новости и задания в цвете своего предмета' },
	{ id: 'outline', label: 'Контур', hint: 'Тонкие рамки без теней — строго и легко' },
	{ id: 'neon', label: 'Неон', hint: 'Светящиеся края в цвет темы и предметов' },
	{ id: 'paper', label: 'Бумага', hint: 'Тёплая бумага с зерном и заголовки с засечками' },
	{ id: 'comic', label: 'Комикс', hint: 'Жирный контур и сдвинутая тень, как в комиксе' }
] as const;

export type Style = (typeof STYLES)[number]['id'];
const STYLE_KEY = 'gb-style';

export function isStyle(v: unknown): v is Style {
	return STYLES.some((s) => s.id === v);
}

export function currentStyle(): Style {
	try {
		const v = localStorage.getItem(STYLE_KEY);
		return isStyle(v) ? v : 'plain';
	} catch {
		return 'plain';
	}
}

/** Меняет стиль сразу, с плавным переходом, и запоминает на этом устройстве. */
export function setStyle(style: Style) {
	const root = document.documentElement;
	root.classList.add('theme-switching');
	if (style === 'plain') root.removeAttribute('data-style');
	else root.setAttribute('data-style', style);
	try {
		if (style === 'plain') localStorage.removeItem(STYLE_KEY);
		else localStorage.setItem(STYLE_KEY, style);
	} catch {
		/* приватный режим — просто не запоминаем */
	}
	setTimeout(() => root.classList.remove('theme-switching'), 300);
}
