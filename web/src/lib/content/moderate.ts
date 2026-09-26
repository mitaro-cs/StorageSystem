import { del, post, put } from '$lib/api';
import { toast, toastError } from '$lib/toasts.svelte';
import type { ModType } from '$lib/types';
import { askText } from '$lib/ui/ask.svelte';

/** Действия модерации и «Пожаловаться»: скрыть, вернуть, удалить — для любой записи. */
const HIDE: Record<ModType, (id: number, hidden: boolean) => Promise<unknown>> = {
	post: (id, hidden) => put(`/api/news/${id}/hidden`, { value: hidden }),
	homework: (id, hidden) => put(`/api/homework/${id}/hidden`, { value: hidden }),
	material: (id, hidden) => post(`/api/materials/${id}/${hidden ? 'hide' : 'unhide'}`),
	comment: (id, hidden) => put(`/api/comments/${id}/hidden`, { value: hidden })
};

const DELETE: Record<ModType, string> = {
	post: '/api/news/',
	homework: '/api/homework/',
	material: '/api/materials/',
	comment: '/api/comments/'
};

/** Скрыть или вернуть: новость, задание, материал или комментарий. */
export function setHidden(type: ModType, id: number, hidden: boolean): Promise<unknown> {
	return HIDE[type](id, hidden);
}

export function remove(type: ModType, id: number): Promise<unknown> {
	return del(DELETE[type] + id);
}

/** «новость», «задание»… — в подписях и вопросах. */
export const KIND: Record<ModType, string> = {
	post: 'новость',
	homework: 'задание',
	material: 'материал',
	comment: 'комментарий'
};

/**
 * Пожаловаться модераторам: они увидят жалобу и пояснение, но не имя. Повторная жалоба того же
 * человека на то же самое не считается.
 */
export async function report(type: ModType, id: number) {
	const reason = await askText('Модераторы группы увидят жалобу и ваше пояснение, но не имя.', {
		title: `Пожаловаться на ${KIND[type]}`,
		label: 'Что не так',
		placeholder: 'Например: грубость, реклама, ошибка в задании',
		ok: 'Отправить',
		maxlength: 500
	});
	if (reason === null) return;
	try {
		await post('/api/reports', { type, id, reason });
		toast('Жалоба отправлена модераторам', 'ok');
	} catch (e) {
		toastError(e);
	}
}
