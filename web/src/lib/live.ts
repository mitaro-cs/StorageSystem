import { enabled as offlineEnabled, offline, syncNow } from './offline/engine';
import { refreshUnread } from './notify.svelte';
import { refreshModeration } from './moderation.svelte';
import { loadMe } from './session.svelte';
import { loadSubjects } from './data.svelte';
import { loadPresence } from './presence.svelte';

/**
 * Живые обновления: сервер сообщает, что что-то изменилось (комментарий, новость, задание,
 * материал, а с 0.9.7 – любое изменение: люди, роли, группа, предметы, настройки), – открытые
 * страницы перечитывают себя сами, без перезагрузки. Соединение держим, пока
 * вкладка на экране: на телефоне через туннель одновременно открыто не больше 6 соединений. Если
 * поток не доходит (туннель копит ответ – «hello» не пришло за 8 секунд), раз в 3 секунды
 * спрашиваем короткий номер состояния (`/api/live/seq`) и перечитываем страницу, только когда он
 * сменился (1.0.2: раньше раз в 10 секунд перечитывали всё вслепую – комментарии запаздывали).
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

	// Несколько изменений подряд (задание с файлами) – одно перечитывание.
	function refresh() {
		clearTimeout(refreshTimer);
		refreshTimer = setTimeout(() => {
			// Страницы перечитывают себя сразу (не все изменения попадают в копию на устройстве –
			// роли, группа, настройки), копия обновляется следом.
			offline.version++;
			if (offline.ready && offlineEnabled()) syncNow();
			refreshModeration();
			// Роли, группы, правила и предметы – в шапке и меню на каждой странице.
			loadMe().catch(() => {});
			loadSubjects().catch(() => {});
		}, 120);
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
			// Переподключились после обрыва, а на сервере уже что-то новое (номер может и уменьшиться –
			// копия на втором компьютере хоста взяла снимок).
			if (lastSeq && seq !== lastSeq) refresh();
			lastSeq = seq;
			loadPresence(true);
		});
		// Кто-то открыл или закрыл сайт (1.0.2) – «на сайте» и точки у участников.
		source.addEventListener('presence', () => loadPresence(true));
		source.addEventListener('change', (e) => {
			lastSeq = seqOf(e as MessageEvent);
			refresh();
		});
		source.addEventListener('bell', () => refreshUnread());
		// Обрыв – EventSource переподключится сам.
	}

	function close() {
		clearTimeout(helloTimer);
		source?.close();
		source = null;
	}

	function fallBack() {
		close();
		polling = true;
		let ticks = 0;
		pollTimer = setInterval(async () => {
			if (document.visibilityState !== 'visible') return;
			if (++ticks % 5 === 0) loadPresence(true);
			try {
				const res = await fetch('/api/live/seq', { credentials: 'same-origin' });
				if (!res.ok) return;
				const seq = Number((await res.json()).seq) || 0;
				if (seq !== lastSeq) {
					lastSeq = seq;
					refresh();
				}
			} catch {
				/* нет связи – спросим через 3 секунды */
			}
		}, 3_000);
	}

	function onVisibility() {
		clearTimeout(hideTimer);
		if (document.visibilityState === 'visible') {
			if (!source && !polling) {
				open();
				refresh();
			} else if (polling) refresh();
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
