import { redirect } from '@sveltejs/kit';
import { ApiError, get } from '$lib/api';
import { loadMe } from '$lib/session.svelte';
import { loadSubjects } from '$lib/data.svelte';

export const load = async ({ fetch, url }) => {
	try {
		const me = await loadMe(fetch);
		if (me.restriction) redirect(307, '/security');
		await loadSubjects();
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
