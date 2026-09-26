import { enabled as offlineEnabled, offline, syncNow } from './offline/engine';
import { refreshUnread } from './notify.svelte';
import { refreshModeration } from './moderation.svelte';

/**
 * Живые обновления: сервер сообщает, что что-то изменилось (комментарий, новость, задание,
 * материал), — открытые страницы перечитывают себя сами, без перезагрузки. Соединение держим, пока
 * вкладка на экране: на телефоне через туннель одновременно открыто не больше 6 соединений. Если
 * поток не доходит (туннель копит ответ — «hello» не пришло за 8 секунд), раз в 20 секунд
 * проверяем сами.
 */
export function startLive(): () => void {
	if (typeof EventSource === 'undefined') return () => {};
	let source: EventSource | null = null;
	let lastSeq = 0;
	let polling = false;
	let helloTimer: ReturnType<typeof setTimeout> | undefined;
	let pollTimer: ReturnType<typeof setInterval> | undefined;
	let hideTimer: ReturnType<typeof setTimeout> | undefined;
	let refreshTimer: ReturnType<typeof setTimeout> | undefined;

	// Несколько изменений подряд (задание с файлами) — одно перечитывание.
	function refresh() {
		clearTimeout(refreshTimer);
		refreshTimer = setTimeout(() => {
			// Копия на устройстве обновится и сама скажет страницам; без неё (окно хоста) — сразу.
			if (offline.ready && offlineEnabled()) syncNow();
			else offline.version++;
			refreshModeration();
		}, 300);
	}

	function seqOf(e: MessageEvent): number {
		try {
			return Number(JSON.parse(e.data).seq) || 0;
		} catch {
			return 0;
		}
	}

	function open() {
		if (source || polling) return;
		source = new EventSource('/api/live');
		helloTimer = setTimeout(fallBack, 8000);
		source.addEventListener('hello', (e) => {
			clearTimeout(helloTimer);
			const seq = seqOf(e as MessageEvent);
			// Переподключились после обрыва, а на сервере уже что-то новое.
			if (lastSeq && seq > lastSeq) refresh();
			lastSeq = seq;
		});
		source.addEventListener('change', (e) => {
			lastSeq = seqOf(e as MessageEvent);
			refresh();
		});
		source.addEventListener('bell', () => refreshUnread());
		// Обрыв — EventSource переподключится сам.
	}

	function close() {
		clearTimeout(helloTimer);
		source?.close();
		source = null;
	}

	function fallBack() {
		close();
		polling = true;
		pollTimer = setInterval(() => {
			if (document.visibilityState === 'visible') refresh();
		}, 20_000);
	}

	function onVisibility() {
		clearTimeout(hideTimer);
		if (document.visibilityState === 'visible') {
			if (!source && !polling) {
				open();
				refresh();
			}
		} else {
			hideTimer = setTimeout(close, 30_000);
		}
	}

	open();
	document.addEventListener('visibilitychange', onVisibility);
	return () => {
		close();
		clearInterval(pollTimer);
		clearTimeout(hideTimer);
		clearTimeout(refreshTimer);
		document.removeEventListener('visibilitychange', onVisibility);
	};
}
