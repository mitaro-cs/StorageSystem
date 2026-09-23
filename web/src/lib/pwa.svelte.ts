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

/** Ответ пришёл из офлайн-кеша service worker. */
export function markCached(res: Response) {
	if (res.headers.get('X-From-Cache') === '1') pwa.offline = true;
}

export function forgetOfflineData() {
	navigator.serviceWorker?.controller?.postMessage('logout');
}
