import { get, post } from '$lib/api';

/** Счётчик непрочитанных для колокольчика: раз в минуту, при возврате на вкладку и после push. */
export const bell = $state({ unread: 0 });

export async function refreshUnread() {
	try {
		bell.unread = (await get<{ count: number }>('/api/notifications/unread')).count;
	} catch {
		/* нет сети — покажем в следующий раз */
	}
}

export function startBell(): () => void {
	refreshUnread();
	const timer = setInterval(() => {
		if (document.visibilityState === 'visible') refreshUnread();
	}, 60_000);
	const onVisible = () => document.visibilityState === 'visible' && refreshUnread();
	const onMessage = (e: MessageEvent) => e.data?.type === 'push' && refreshUnread();
	document.addEventListener('visibilitychange', onVisible);
	navigator.serviceWorker?.addEventListener('message', onMessage);
	return () => {
		clearInterval(timer);
		document.removeEventListener('visibilitychange', onVisible);
		navigator.serviceWorker?.removeEventListener('message', onMessage);
	};
}

export async function markRead(ids: number[]) {
	bell.unread = (await post<{ count: number }>('/api/notifications/read', { ids })).count;
}

export async function markAllRead() {
	bell.unread = (await post<{ count: number }>('/api/notifications/read', { all: true })).count;
}
