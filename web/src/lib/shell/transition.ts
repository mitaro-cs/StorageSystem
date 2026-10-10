import type { OnNavigate } from '@sveltejs/kit';

/**
 * Переход между разделами (1.0.1): новая страница въезжает сбоку – по порядку разделов в нижней
 * панели, глубже – справа, назад – слева. View Transitions: анимируется снимок, а не живая
 * разметка, поэтому ничего не сдвигается и не мерцает; нет поддержки – раздел сменяется сразу.
 */
const ORDER = ['', 'news', 'homework', 'schedule', 'subjects', 'materials', 'profile', 'settings'];
const SUBJECT = /^\/subjects\/\d+$/;
/** Идущий переход: быстрые переходы подряд прерывают прежний – его конец не трогает метку нового. */
let current: ViewTransition | undefined;

function place(path: string): [number, number] {
	const parts = path.split('/').filter(Boolean);
	return [ORDER.indexOf(parts[0] ?? ''), parts.length];
}

/** Куда въезжает страница: назад – слева, глубже или правее в панели – справа, соседняя – на месте. */
export function direction(from: string, to: string, back: boolean): 'fwd' | 'back' | 'fade' {
	const [a, da] = place(from);
	const [b, db] = place(to);
	if (back || (a === b ? db < da : a >= 0 && b >= 0 && b < a)) return 'back';
	return a === b && db === da ? 'fade' : 'fwd';
}

export function transition(nav: OnNavigate): Promise<void> | void {
	const from = nav.from?.url.pathname;
	const to = nav.to?.url.pathname;
	const root = document.documentElement;
	if (
		!document.startViewTransition ||
		!from ||
		!to ||
		from === to ||
		(SUBJECT.test(from) && SUBJECT.test(to)) || // предмет → предмет анимирует сама страница
		matchMedia('(prefers-reduced-motion: reduce)').matches ||
		// Safari в браузере: жест «назад» уже анимирован системой – второй раз не нужно.
		(nav.type === 'popstate' &&
			navigator.maxTouchPoints > 0 &&
			!matchMedia('(display-mode: standalone)').matches)
	)
		return;
	root.dataset.nav = direction(from, to, nav.type === 'popstate');
	return new Promise((resolve) => {
		const t = document.startViewTransition(async () => {
			resolve();
			// Переход могли прервать следующим (страница сразу заменила адрес) – это не ошибка.
			await nav.complete.catch(() => {});
		});
		current = t;
		t.ready.catch(() => {});
		t.finished.finally(() => {
			if (current === t) delete root.dataset.nav;
		});
	});
}
