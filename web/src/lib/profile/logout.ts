import { goto } from '$app/navigation';
import { ApiError, request } from '$lib/api';
import { clearCache } from '$lib/cache';
import { offline, wipeOffline } from '$lib/offline/engine';
import { forgetOfflineData } from '$lib/pwa.svelte';
import { clearRecent } from '$lib/recent';
import { session } from '$lib/session.svelte';
import { toastError } from '$lib/toasts.svelte';
import { ask } from '$lib/ui/ask.svelte';

/**
 * Выйти на этом устройстве (кнопка внизу меню профиля): всё, что хранилось здесь, стирается –
 * копия данных, файлы, недавнее. Сначала уходим со страницы, потом забываем пользователя.
 */
export async function logout() {
	if (
		offline.pending > 0 &&
		!(await ask(
			`Без сети сделано действий: ${offline.pending}. Они ещё не отправлены и пропадут. Выйти?`,
			{ title: 'Выйти из аккаунта', ok: 'Выйти', danger: true }
		))
	)
		return;
	try {
		await request('/api/auth/logout', { method: 'POST', body: {} });
	} catch (err) {
		return toastError(
			err instanceof ApiError && err.status === 0
				? new Error('Выйти можно, когда появится интернет')
				: err
		);
	}
	forgetOfflineData();
	await wipeOffline();
	clearCache();
	clearRecent();
	await goto('/login', { replaceState: true });
	session.me = null;
}
