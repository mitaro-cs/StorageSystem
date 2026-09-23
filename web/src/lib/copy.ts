import { toast } from './toasts.svelte';
import { session } from './session.svelte';

/**
 * Адрес сайта для участников: на компьютере хоста окно открыто по 127.0.0.1, а ссылки и QR должны
 * вести на адрес, который видят одногруппники.
 */
export const siteUrl = () => session.me?.instance.publicUrl ?? location.origin;

/** Абсолютная ссылка на путь приложения (сервер отдаёт только путь). */
export const absolute = (path: string) => siteUrl() + path;

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
