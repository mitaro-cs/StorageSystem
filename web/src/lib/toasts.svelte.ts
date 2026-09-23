export interface Toast {
	id: number;
	text: string;
	kind: 'info' | 'ok' | 'error';
}

let seq = 0;
export const toasts = $state<Toast[]>([]);

export function toast(text: string, kind: Toast['kind'] = 'info', ms = 3500) {
	const id = ++seq;
	toasts.push({ id, text, kind });
	setTimeout(() => dismiss(id), ms);
}

export function dismiss(id: number) {
	const i = toasts.findIndex((t) => t.id === id);
	if (i >= 0) toasts.splice(i, 1);
}

/** Показать ошибку API (или любую) как тост. */
export function toastError(e: unknown) {
	toast(e instanceof Error ? e.message : 'Что-то пошло не так', 'error', 5000);
}
