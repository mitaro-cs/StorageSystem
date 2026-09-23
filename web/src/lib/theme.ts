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
