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
 * Дизайн (Настройки → Оформление): материал интерфейса — карточки, тени, фон, заголовки. Пять
 * цельных вариантов; «Классика» — как было всегда. Цвет у каждого — из выбранного основного цвета
 * (lib/colors.ts). Правила — в app.css (:root[data-style]).
 */
export const STYLES = [
	{ id: 'plain', label: 'Классика', hint: 'Строго и чисто — как было всегда' },
	{ id: 'glass', label: 'Стекло', hint: 'Матовые панели над мягкими пятнами света' },
	{ id: 'depth', label: 'Объём', hint: 'Свет сверху, мягкие тени, выпуклые кнопки' },
	{ id: 'neon', label: 'Сияние', hint: 'Светящиеся кромки в цвет темы и предметов' },
	{ id: 'paper', label: 'Бумага', hint: 'Тёплая бумага, чернила и заголовки с засечками' }
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
