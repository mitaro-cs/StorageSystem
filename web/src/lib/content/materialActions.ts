import { del, post } from '$lib/api';
import { toast, toastError } from '$lib/toasts.svelte';
import type { Material } from '$lib/types';
import type { MenuItem } from '$lib/ui/Menu.svelte';

export function materialActions(m: Material, changed: () => void): MenuItem[] {
	const out: MenuItem[] = [];
	const act = (path: string, message: string) => async () => {
		try {
			await post(`/api/materials/${m.id}/${path}`);
			toast(message, 'ok');
			changed();
		} catch (e) {
			toastError(e);
		}
	};
	if (m.file) {
		out.push({
			label: 'Скачать',
			onclick: () => (location.href = `/api/files/${m.file!.id}?download=true`)
		});
	}
	if (m.can.moderate && m.status === 'pending') {
		out.push({ label: 'Одобрить', onclick: act('approve', 'Материал опубликован') });
		out.push({ label: 'Отклонить', danger: true, onclick: act('reject', 'Материал отклонён') });
	}
	if (m.can.moderate && m.status === 'published') {
		out.push(
			m.hidden
				? { label: 'Вернуть', onclick: act('unhide', 'Материал снова виден') }
				: { label: 'Скрыть', onclick: act('hide', 'Материал скрыт') }
		);
	}
	if (m.can.delete) {
		out.push({
			label: 'Удалить',
			danger: true,
			onclick: async () => {
				if (!confirm(`Удалить «${m.title}»?`)) return;
				try {
					await del(`/api/materials/${m.id}`);
					toast('Удалено', 'ok');
					changed();
				} catch (e) {
					toastError(e);
				}
			}
		});
	}
	return out;
}
