import { del, patch, post } from './api';
import { DEFAULT_SAT, currentAccent, setAccent } from './colors';
import {
	backgroundBlur,
	backgroundDim,
	currentBackground,
	currentIcon,
	customBackground,
	isAppIcon,
	removeCustomBackground,
	setBackgroundBlur,
	setBackgroundDim,
	setCustomBackground,
	setIcon
} from './looks';
import type { Me } from './types';
import { currentStyle, currentTheme, isStyle, setStyle, setTheme, type Theme } from './theme';

/**
 * Оформление общее для всех устройств человека (0.6): режим, дизайн, цвет, своя картинка фона
 * (приглушение и размытие) и значок. На устройстве всё по-прежнему лежит в localStorage (скрипт в
 * app.html ставит его до отрисовки), а сервер хранит копию: что новее (метка at), то и действует.
 * Грузится отдельно — нужен после входа и при смене оформления.
 */

const AT_KEY = 'gb-looks-at';
/** Какая картинка с сервера сейчас на устройстве: не скачивать её заново. */
const BG_ID_KEY = 'gb-bg-id';

interface Looks {
	theme?: Theme;
	style?: string;
	hue?: number | null;
	sat?: number;
	dim?: number;
	blur?: boolean;
	icon?: string;
	at?: number;
}

function read(key: string): string | null {
	try {
		return localStorage.getItem(key);
	} catch {
		return null;
	}
}

function write(key: string, value: string | null) {
	try {
		if (value === null) localStorage.removeItem(key);
		else localStorage.setItem(key, value);
	} catch {
		/* приватный режим — синхронизация просто не запоминается */
	}
}

/** Применяем чужое (с сервера) — свои же события «оформление изменилось» не отправляем обратно. */
let applying = false;
export const isApplying = () => applying;

function local(): Looks {
	const a = currentAccent();
	return {
		theme: currentTheme(),
		style: currentStyle(),
		hue: a.hue,
		sat: a.sat,
		dim: backgroundDim(),
		blur: backgroundBlur(),
		icon: currentIcon()
	};
}

let timer: ReturnType<typeof setTimeout> | undefined;

/** Оформление поменяли здесь — через секунду (ползунки двигают часто) отправляем на сервер. */
export function pushLooks() {
	if (applying) return;
	clearTimeout(timer);
	timer = setTimeout(() => {
		const at = Date.now();
		write(AT_KEY, String(at));
		patch('/api/me/preferences', { appearance: { ...local(), at } }).catch(() => {});
	}, 1000);
}

function csrf(): string {
	return document.cookie.match(/(?:^|;\s*)(?:__Host-)?gb_csrf=([^;]+)/)?.[1] ?? '';
}

/** data: URL → Blob без fetch (CSP разрешает запросы только к своему серверу). */
function toBlob(data: string): Blob {
	const [head, body] = data.split(',', 2);
	const bytes = Uint8Array.from(atob(body), (c) => c.charCodeAt(0));
	return new Blob([bytes], { type: head.match(/data:([^;]+)/)?.[1] ?? 'image/jpeg' });
}

let sentIcon: string | null = null;

/**
 * Окно приложения хоста: выбранный значок — сразу в Dock на Mac или на панель задач Windows (сервер
 * передаёт его оболочке, она запоминает выбор до следующего запуска).
 */
export function hostIcon() {
	const icon = currentIcon();
	if (icon === sentIcon) return;
	sentIcon = icon;
	post('/api/desktop/icon', { icon }).catch(() => (sentIcon = null));
}

/** Картинка, которая сейчас на фоне, — на сервер (после выбора здесь). */
export function uploadCurrentBackground() {
	const data = customBackground();
	if (data) uploadBackground(toBlob(data));
}

/** Своя картинка выбрана здесь — копия на сервер, чтобы появилась и на других устройствах. */
export async function uploadBackground(image: Blob) {
	try {
		const r = await fetch('/api/me/background', {
			method: 'PUT',
			body: image,
			headers: { 'X-CSRF-Token': csrf(), 'Content-Type': 'application/octet-stream' }
		});
		if (r.ok) write(BG_ID_KEY, (await r.json()).background);
		else {
			// Картинка не подошла серверу — на этом устройстве она есть, на других не появится.
			const { toast } = await import('./toasts.svelte');
			const why = await r.json().catch(() => null);
			toast(why?.message ?? 'Картинка не сохранилась для других устройств', 'error');
		}
	} catch {
		/* нет сети — останется только на этом устройстве, при следующем входе отправим снова */
	}
}

/** Картинку убрали здесь — убираем и на сервере. */
export async function removeBackground() {
	write(BG_ID_KEY, null);
	await del('/api/me/background').catch(() => {});
}

function apply(s: Looks) {
	applying = true;
	try {
		if (s.theme && s.theme !== currentTheme()) setTheme(s.theme);
		if (isStyle(s.style) && s.style !== currentStyle()) setStyle(s.style);
		if (s.hue !== undefined || s.sat !== undefined) {
			const a = currentAccent();
			const next = { hue: s.hue === undefined ? a.hue : s.hue, sat: s.sat ?? DEFAULT_SAT };
			if (next.hue !== a.hue || next.sat !== a.sat) setAccent(next, false);
		}
		if (typeof s.dim === 'number' && s.dim !== backgroundDim()) setBackgroundDim(s.dim);
		if (typeof s.blur === 'boolean' && s.blur !== backgroundBlur()) setBackgroundBlur(s.blur);
		if (isAppIcon(s.icon) && s.icon !== currentIcon()) setIcon(s.icon);
	} finally {
		applying = false;
	}
}

/**
 * После входа: на сервере новее — применяем здесь, здесь новее (или на сервере пусто) —
 * отправляем. Картинка фона — так же: другая на сервере — скачиваем, своя ещё не отправлена —
 * отправляем.
 */
export async function pullLooks(me: Me) {
	const server = me.user.appearance as Looks | null | undefined;
	if (server === undefined) return; // сервер до 0.6
	const mine = Number(read(AT_KEY) ?? 0);
	if (server?.at && server.at > mine) {
		apply(server);
		write(AT_KEY, String(server.at));
	} else if (!server?.at || server.at < mine) pushLooks();

	const id = me.user.background ?? null;
	const have = read(BG_ID_KEY);
	if (id && id !== have) {
		try {
			const r = await fetch(`/api/avatars/${id}-1920.webp`);
			if (r.ok && (await setCustomBackground(await r.blob()))) write(BG_ID_KEY, id);
		} catch {
			/* нет сети — попробуем при следующем открытии */
		}
	} else if (!id && have) {
		// Убрали на другом устройстве.
		write(BG_ID_KEY, null);
		removeCustomBackground();
	} else if (!id && currentBackground() === 'custom') {
		// Картинка выбрана до 0.6 — только на этом устройстве: отправляем.
		uploadCurrentBackground();
	}
}
