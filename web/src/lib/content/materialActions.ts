import { del, post, put } from '$lib/api';
import { toast, toastError } from '$lib/toasts.svelte';
import type { Material } from '$lib/types';
import type { MenuItem } from '$lib/ui/Menu.svelte';
import { ask } from '$lib/ui/ask.svelte';
import { report } from '$lib/content/moderate';
import { session } from '$lib/session.svelte';

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
	// Закрепить сверху — староста, замы и модераторы (как закреплённые новости).
	if (m.can.pin && m.status === 'published') {
		out.push({
			label: m.pinnedAt ? 'Открепить' : 'Закрепить сверху',
			onclick: async () => {
				try {
					await put(`/api/materials/${m.id}/pinned`, { pinned: !m.pinnedAt });
					toast(m.pinnedAt ? 'Откреплено' : 'Закреплено сверху', 'ok');
					changed();
				} catch (e) {
					toastError(e);
				}
			}
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
	if (
		!m.can.moderate &&
		m.author.id !== session.me?.user.id &&
		m.id > 0 &&
		m.status === 'published'
	)
		out.push({ label: 'Пожаловаться', onclick: () => report('material', m.id) });
	if (m.can.delete) {
		out.push({
			label: 'Удалить',
			danger: true,
			onclick: async () => {
				if (!(await ask(`Удалить «${m.title}»?`, { ok: 'Удалить', danger: true }))) return;
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
