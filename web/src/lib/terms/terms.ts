import { get, patch } from '$lib/api';

/** Правила сайта: версия, дописанное администратором (HTML уже очищен сервером) и когда. */
export interface TermsView {
	version: string;
	extraMd: string;
	extraHtml: string;
	updatedAt: number | null;
}

export const loadTerms = () => get<TermsView>('/api/terms', { anonymous: true });

/** Принять действующие правила (после регистрации, первой настройки или в окне согласия). */
export async function acceptTerms(version?: string): Promise<void> {
	const v = version ?? (await loadTerms()).version;
	await patch('/api/me/preferences', { terms: v });
}
