/**
 * Подтверждения и короткие вопросы — окном приложения вместо confirm() и prompt() браузера. Окно
 * приложения хоста на Mac их не показывает вовсе (WKWebView: prompt() сразу возвращает null), а
 * плагин диалогов Tauri подменяет confirm() асинхронной функцией — проверка `if (!confirm(…))`
 * получала обещание, то есть «да», и удаляла без вопроса. Показывает окно lib/ui/Dialogs.svelte.
 */

export interface AskOptions {
	/** Заголовок окна; по умолчанию — «Подтвердите». */
	title?: string;
	/** Надпись на кнопке согласия. */
	ok?: string;
	/** Красная кнопка — для удаления и других необратимых действий. */
	danger?: boolean;
	/** Надпись на кнопке отказа; по умолчанию — «Отмена». */
	cancel?: string;
}

export interface AskTextOptions extends AskOptions {
	/** Что уже вписано в поле. */
	value?: string;
	label?: string;
	placeholder?: string;
	inputmode?: 'text' | 'numeric';
	autocomplete?: AutoFill;
	maxlength?: number;
}

type Pending =
	| { kind: 'confirm'; message: string; options: AskOptions; resolve: (v: boolean) => void }
	| {
			kind: 'text';
			message: string;
			options: AskTextOptions;
			resolve: (v: string | null) => void;
	  };

export const dialog = $state<{ current: Pending | null }>({ current: null });

/** Отменяет открытый вопрос: новый важнее. */
function dropCurrent() {
	const p = dialog.current;
	dialog.current = null;
	if (p?.kind === 'confirm') p.resolve(false);
	else p?.resolve(null);
}

/** «Да» — true, «Отмена», Esc или клик мимо — false. */
export function ask(message: string, options: AskOptions = {}): Promise<boolean> {
	dropCurrent();
	return new Promise((resolve) => {
		dialog.current = { kind: 'confirm', message, options, resolve };
	});
}

/** Введённый текст без пробелов по краям; пусто или отмена — null. */
export function askText(message: string, options: AskTextOptions = {}): Promise<string | null> {
	dropCurrent();
	return new Promise((resolve) => {
		dialog.current = { kind: 'text', message, options, resolve };
	});
}

/** Ответ из окна: true/false для подтверждения, строка или null для вопроса с полем. */
export function answer(value: boolean | string | null) {
	const p = dialog.current;
	dialog.current = null;
	if (!p) return;
	if (p.kind === 'confirm') p.resolve(value === true);
	else p.resolve(typeof value === 'string' && value.trim() ? value.trim() : null);
}
