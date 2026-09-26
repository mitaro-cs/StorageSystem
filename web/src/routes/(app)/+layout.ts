import { redirect } from '@sveltejs/kit';
import { ApiError, get } from '$lib/api';
import { loadMe } from '$lib/session.svelte';
import { loadSubjects } from '$lib/data.svelte';

export const load = async ({ fetch, url }) => {
	// Предметы — вместе с профилем, а не после: на телефоне через туннель каждый запрос — задержка.
	// При 401 на вход отправляет запрос профиля (или на первичную настройку).
	const subjects = loadSubjects({ quiet401: true }).then(
		() => null,
		(e: unknown) => e
	);
	try {
		const me = await loadMe(fetch);
		if (me.restriction) redirect(307, '/security');
		const failed = await subjects;
		if (failed) throw failed;
		return { me };
	} catch (e) {
		if (e instanceof ApiError && e.status === 401) {
			const setup = await get<{ needed: boolean }>('/api/setup', { fetch, anonymous: true });
			if (setup.needed) redirect(307, '/setup');
			redirect(307, `/login?next=${encodeURIComponent(url.pathname + url.search)}`);
		}
		throw e;
	}
};
