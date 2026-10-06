import { dev } from '$app/environment';

/**
 * Состояние сети и установка PWA. offline – сервер сейчас недоступен (показываются сохранённые
 * данные); network – есть ли у устройства интернет: если есть, значит выключен компьютер хоста.
 */
export const pwa = $state({ offline: false, canInstall: false, network: true });

interface InstallEvent extends Event {
	prompt: () => Promise<void>;
}

let deferred: InstallEvent | null = null;

export function initPwa() {
	if (typeof window === 'undefined') return;
	pwa.offline = !navigator.onLine;
	pwa.network = navigator.onLine;
	addEventListener('online', () => ((pwa.offline = false), (pwa.network = true)));
	addEventListener('offline', () => ((pwa.offline = true), (pwa.network = false)));
	addEventListener('appinstalled', () => markInstalled());
	addEventListener('beforeinstallprompt', (e) => {
		e.preventDefault();
		deferred = e as InstallEvent;
		pwa.canInstall = true;
	});
}

export async function install() {
	if (!deferred) return;
	await deferred.prompt();
	deferred = null;
	pwa.canInstall = false;
}

/** Service worker: офлайн-режим и push. Регистрирует интерфейс сам – и не в окне хоста. */
export function registerServiceWorker() {
	if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) return;
	const sw = navigator.serviceWorker;
	sw.register('/service-worker.js', { type: dev ? 'module' : 'classic' })
		.then(() => sw.ready)
		.then((reg) => warmLater(() => reg.active))
		.catch(() => {});
	// Пришла новая версия – докачать и её, а открытую страницу перезагрузить: установленное на
	// экран «Домой» приложение почти не перезапускается (iPhone возвращает его из фона), и без
	// этого оставалось на старой версии.
	let updating = !!sw.controller;
	sw.addEventListener('controllerchange', () => {
		warmLater(() => sw.controller);
		if (updating) reloadWhenSafe();
		updating = true;
	});
	// Новую версию ищем при каждом возврате в приложение и раз в полчаса.
	const check = () =>
		sw
			.getRegistration()
			.then((r) => r?.update())
			.catch(() => {});
	addEventListener('visibilitychange', () => {
		if (document.visibilityState === 'visible') check();
	});
	setInterval(check, 30 * 60_000);
}

/**
 * Перезагрузить на новую версию, не мешая: сейчас – если человек ничего не печатает и не открыто
 * окно с формой, иначе – как только приложение уйдёт с экрана.
 */
function reloadWhenSafe() {
	const busy = () => {
		const el = document.activeElement as HTMLElement | null;
		const typing = !!el && (el.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName));
		return typing || !!document.querySelector('[aria-modal="true"]');
	};
	if (document.visibilityState === 'hidden' || !busy()) {
		location.reload();
		return;
	}
	addEventListener('visibilitychange', function onHide() {
		if (document.visibilityState !== 'hidden') return;
		removeEventListener('visibilitychange', onHide);
		location.reload();
	});
}

/**
 * Остальные файлы приложения service worker докачивает фоном – когда первый экран уже открыт,
 * чтобы его запросы не стояли в очереди за сотней файлов.
 */
function warmLater(worker: () => ServiceWorker | null | undefined) {
	const go = () => setTimeout(() => worker()?.postMessage('warm'), 2000);
	if (document.readyState === 'complete') go();
	else addEventListener('load', go, { once: true });
}

/**
 * Окно приложения хоста: сервер на этом же компьютере – service worker и его кеш не нужны. Прежние
 * версии их регистрировали (и service worker мог зациклить вход окна), поэтому убираем остатки.
 */
export async function forgetServiceWorker() {
	try {
		const regs = (await navigator.serviceWorker?.getRegistrations()) ?? [];
		await Promise.all(regs.map((r) => r.unregister()));
		const keys = typeof caches === 'undefined' ? [] : await caches.keys();
		await Promise.all(keys.map((k) => caches.delete(k)));
	} catch {
		/* нечего убирать */
	}
}

export function forgetOfflineData() {
	navigator.serviceWorker?.controller?.postMessage('logout');
}

const INSTALLED = 'gb-installed';

export function markInstalled() {
	try {
		localStorage.setItem(INSTALLED, '1');
	} catch {
		/* не запоминаем */
	}
}

/** Приложение установлено: открыто с экрана «Домой» или браузер сообщил об установке. */
export function installed(): boolean {
	if (matchMedia('(display-mode: standalone)').matches) return true;
	if ((navigator as Navigator & { standalone?: boolean }).standalone) return true;
	try {
		return localStorage.getItem(INSTALLED) === '1';
	} catch {
		return false;
	}
}
