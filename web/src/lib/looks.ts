import { appIcon, iconSrc, type AppIcon } from './appIcon.svelte';

/**
 * Фон страниц (Профиль → Оформление): градиенты и узоры подстраиваются под светлую и тёмную тему,
 * своя картинка хранится на этом устройстве (уменьшенной, в localStorage). Правила — в app.css
 * (:root[data-bg]), применяется до отрисовки скриптом в app.html.
 */
export const BACKGROUNDS = [
	{ id: 'none', label: 'Без фона' },
	{ id: 'aurora', label: 'Аврора' },
	{ id: 'sunset', label: 'Закат' },
	{ id: 'ocean', label: 'Океан' },
	{ id: 'mint', label: 'Мята' },
	{ id: 'grid', label: 'Клетка' },
	{ id: 'dots', label: 'Точки' },
	{ id: 'lines', label: 'Линейка' },
	{ id: 'custom', label: 'Своя картинка' }
] as const;

export type Background = (typeof BACKGROUNDS)[number]['id'];
const BG_KEY = 'gb-bg';
const BG_IMAGE_KEY = 'gb-bg-image';

export function isBackground(v: unknown): v is Background {
	return BACKGROUNDS.some((b) => b.id === v);
}

export function currentBackground(): Background {
	try {
		const v = localStorage.getItem(BG_KEY);
		return isBackground(v) ? v : 'none';
	} catch {
		return 'none';
	}
}

/** Своя картинка фона (data: URL) или null. */
export function customBackground(): string | null {
	try {
		return localStorage.getItem(BG_IMAGE_KEY);
	} catch {
		return null;
	}
}

function applyBackground(bg: Background, image: string | null) {
	const root = document.documentElement;
	if (bg === 'none' || (bg === 'custom' && !image)) root.removeAttribute('data-bg');
	else root.setAttribute('data-bg', bg);
	if (bg === 'custom' && image) root.style.setProperty('--bg-image', `url("${image}")`);
	else root.style.removeProperty('--bg-image');
}

export function setBackground(bg: Background) {
	try {
		if (bg === 'none') localStorage.removeItem(BG_KEY);
		else localStorage.setItem(BG_KEY, bg);
	} catch {
		/* приватный режим — просто не запоминаем */
	}
	applyBackground(bg, bg === 'custom' ? customBackground() : null);
}

/**
 * Своя картинка: уменьшаем до 1600 точек по длинной стороне и сохраняем JPEG — чтобы поместилась в
 * хранилище браузера и не тормозила. Возвращает false, если сохранить не вышло (мало места).
 */
export async function setCustomBackground(file: Blob): Promise<boolean> {
	const bitmap = await createImageBitmap(file);
	const k = Math.min(1, 1600 / Math.max(bitmap.width, bitmap.height));
	const canvas = document.createElement('canvas');
	canvas.width = Math.round(bitmap.width * k);
	canvas.height = Math.round(bitmap.height * k);
	canvas.getContext('2d')!.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
	bitmap.close();
	const data = canvas.toDataURL('image/jpeg', 0.8);
	try {
		localStorage.setItem(BG_IMAGE_KEY, data);
		localStorage.setItem(BG_KEY, 'custom');
	} catch {
		return false;
	}
	applyBackground('custom', data);
	return true;
}

/**
 * Значок сайта (Профиль → Оформление): во вкладке меняется сразу, на экране «Домой» — при установке
 * (Android обновляет сам, iPhone — если добавить сайт заново). Файлы — static/icons/v и
 * manifest-*.webmanifest (java scripts/Icons.java variants), применяется скриптом в app.html.
 */
export const ICONS = [
	{ id: 'light', label: 'Светлый' },
	{ id: 'dark', label: 'Тёмный' },
	{ id: 'ocean', label: 'Океан' },
	{ id: 'forest', label: 'Лес' },
	{ id: 'sunset', label: 'Закат' },
	{ id: 'grape', label: 'Виноград' }
] as const;

const ICON_KEY = 'gb-icon';

export function isAppIcon(v: unknown): v is AppIcon {
	return ICONS.some((i) => i.id === v);
}

export function currentIcon(): AppIcon {
	try {
		const v = localStorage.getItem(ICON_KEY);
		return isAppIcon(v) ? v : 'light';
	} catch {
		return 'light';
	}
}

export function setIcon(icon: AppIcon) {
	try {
		if (icon === 'light') localStorage.removeItem(ICON_KEY);
		else localStorage.setItem(ICON_KEY, icon);
	} catch {
		/* приватный режим — просто не запоминаем */
	}
	const light = icon === 'light';
	const link = (rel: string) => document.querySelector<HTMLLinkElement>(`link[rel="${rel}"]`);
	link('icon')?.setAttribute('href', iconSrc(icon));
	link('apple-touch-icon')?.setAttribute(
		'href',
		light ? '/icons/icon-180.png?v=3' : `/icons/v/${icon}-180.png?v=3`
	);
	link('manifest')?.setAttribute(
		'href',
		light ? '/manifest.webmanifest' : `/manifest-${icon}.webmanifest`
	);
	appIcon.id = icon;
}
