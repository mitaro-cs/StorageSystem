/** Состояние сети и установка PWA. */
export const pwa = $state({ offline: false, canInstall: false });

interface InstallEvent extends Event {
	prompt: () => Promise<void>;
}

let deferred: InstallEvent | null = null;

export function initPwa() {
	if (typeof window === 'undefined') return;
	pwa.offline = !navigator.onLine;
	addEventListener('online', () => (pwa.offline = false));
	addEventListener('offline', () => (pwa.offline = true));
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
