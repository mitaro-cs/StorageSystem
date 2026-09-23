import { del, patch, put } from '$lib/api';
import { toast, toastError } from '$lib/toasts.svelte';
import type { NewsItem } from '$lib/types';
import type { MenuItem } from '$lib/ui/Menu.svelte';

/** Пункты меню новости по правам из ответа сервера. */
export function newsActions(
	item: NewsItem,
	handlers: { edit: () => void; removed: () => void; changed: () => void }
): MenuItem[] {
	const out: MenuItem[] = [];
	if (item.can.edit) out.push({ label: 'Изменить', onclick: handlers.edit });
	if (item.can.edit)
		out.push({
			label: item.pinned ? 'Открепить' : 'Закрепить',
			onclick: async () => {
				try {
					await patch(`/api/news/${item.id}`, { pinned: !item.pinned });
					handlers.changed();
				} catch (e) {
					toastError(e);
				}
			}
		});
	if (item.can.hide)
		out.push({
			label: item.hidden ? 'Вернуть в ленту' : 'Скрыть',
			onclick: async () => {
				try {
					await put(`/api/news/${item.id}/hidden`, { value: !item.hidden });
					toast(item.hidden ? 'Новость снова видна' : 'Новость скрыта', 'ok');
					handlers.changed();
				} catch (e) {
					toastError(e);
				}
			}
		});
	if (item.can.delete)
		out.push({
			label: 'Удалить',
			danger: true,
			onclick: async () => {
				if (!confirm('Удалить новость? Это нельзя отменить.')) return;
				try {
					await del(`/api/news/${item.id}`);
					toast('Новость удалена', 'ok');
					handlers.removed();
				} catch (e) {
					toastError(e);
				}
			}
		});
	return out;
}
