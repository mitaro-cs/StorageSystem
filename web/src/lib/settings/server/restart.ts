import { request } from '$lib/api';

/**
 * Сервер перезапускается (восстановление из копии, смена сети): ждём, пока он пропадёт и снова
 * ответит, затем перезагружаем страницу. В приложении хоста окно перезагрузит сама оболочка.
 */
export async function waitForRestart(timeoutMs = 120_000): Promise<void> {
	const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
	const alive = async () => {
		try {
			await request('/api/health');
			return true;
		} catch {
			return false;
		}
	};
	const start = Date.now();
	// Сначала сервер должен уйти (или хотя бы пройти пара секунд).
	while (Date.now() - start < 5_000 && (await alive())) await sleep(700);
	while (Date.now() - start < timeoutMs) {
		await sleep(1_000);
		if (await alive()) {
			location.reload();
			return;
		}
	}
	location.reload();
}
