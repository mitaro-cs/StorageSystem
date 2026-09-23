import { toast } from './toasts.svelte';

/** Абсолютная ссылка на путь приложения (сервер отдаёт только путь). */
export const absolute = (path: string) => location.origin + path;

export async function copy(text: string, message = 'Скопировано') {
	try {
		await navigator.clipboard.writeText(text);
		toast(message, 'ok');
	} catch {
		toast('Не удалось скопировать — выделите текст вручную', 'error');
	}
}

/** Системное меню «Поделиться» (на телефоне — сразу в чат группы). */
export const canShare = () => typeof navigator !== 'undefined' && 'share' in navigator;

export async function share(url: string, title: string) {
	try {
		await navigator.share({ title, url });
	} catch {
		/* пользователь закрыл меню */
	}
}
