import { patch } from './api';
import { session } from './session.svelte';

/**
 * Тур по сайту (lib/tour/Tour.svelte): сам – один раз новичку, дальше – из профиля («Как
 * пользоваться»). Пройден или пропущен – сервер помнит (users.onboarded_at), на всех устройствах.
 */
export const welcome = $state({ open: false });

/** Новичок: сервер говорит, что тур ещё не показан. */
export function needsWelcome(): boolean {
	return session.me?.user.onboarded === false;
}

/** Тур пройден или пропущен – больше сам не открывается (и на других устройствах). */
export async function finishWelcome() {
	welcome.open = false;
	if (session.me && session.me.user.onboarded === false) {
		session.me.user.onboarded = true;
		await patch('/api/me/preferences', { onboarded: true }).catch(() => {});
	}
}

/**
 * Подсказка при первом заходе в раздел (lib/tour/Tip.svelte): только новичкам – у тех, кто
 * пользовался сайтом до 0.6, сервер отдаёт «*». Старый сервер без поля – не показываем.
 */
export function tipOpen(id: string): boolean {
	const tips = session.me?.user.tips;
	return !!tips && !tips.includes('*') && !tips.includes(id);
}

/** Закрыть подсказку раздела навсегда (на всех устройствах). */
export function closeTip(id: string) {
	const tips = session.me?.user.tips;
	if (!tips || tips.includes(id)) return;
	tips.push(id);
	patch('/api/me/preferences', { tip: id }).catch(() => {});
}
