import { patch } from './api';
import { session } from './session.svelte';

/** Окно знакомства: само — один раз после регистрации, дальше — из профиля. */
export const welcome = $state({ open: false });

/** Новичок: сервер говорит, что знакомство ещё не показано. */
export function needsWelcome(): boolean {
	return session.me?.user.onboarded === false;
}

/** Прочитано — больше само не открывается (и на других устройствах). */
export async function finishWelcome() {
	welcome.open = false;
	if (session.me && session.me.user.onboarded === false) {
		session.me.user.onboarded = true;
		await patch('/api/me/preferences', { onboarded: true }).catch(() => {});
	}
}
