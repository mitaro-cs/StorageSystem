import { expect, test } from '@playwright/test';
import { STUDENT, login, watchConsole } from './helpers';

test('service worker не зацикливает переход на служебный адрес сервера', async ({ page }) => {
	const errors = watchConsole(page);
	await login(page, STUDENT);
	// Интерфейс сам регистрирует service worker после входа (в окне хоста — нет).
	await page.waitForFunction(async () => {
		const reg = await navigator.serviceWorker.getRegistration();
		return !!reg?.active;
	});
	await page.reload();
	await page.waitForFunction(() => !!navigator.serviceWorker.controller);

	// Раньше service worker подменял ответ сервера сохранённой страницей, и окно хоста бесконечно
	// возвращалось на /api/desktop/enter. Теперь такие переходы идут прямо на сервер.
	let navigations = 0;
	page.on('framenavigated', (f) => {
		if (f === page.mainFrame()) navigations++;
	});
	await page.goto('/api/desktop/enter?t=not-a-real-token');
	await expect(page).not.toHaveURL(/\/api\/desktop\/enter/, { timeout: 10_000 });
	await page.waitForTimeout(1500);
	expect(navigations).toBeLessThan(5);
	// Страница для чужого браузера — без уборки копии данных.
	expect(await page.evaluate(() => localStorage.getItem('gb-last-user'))).not.toBeNull();
	expect(errors.filter((e) => !/Failed to load resource/.test(e))).toEqual([]);
});

test('сайт не открылся — заставка называет причину и код, «Сбросить кэш» помогает', async ({
	page
}) => {
	// Код интерфейса не приходит (как при сломанном кеше): заставка не должна крутиться вечно.
	await page.route('**/_app/immutable/**', (r) => r.abort());
	await page.goto('/login');
	const splash = page.locator('#splash');
	await expect(splash.getByText('Не получается открыть groupbase')).toBeVisible({
		timeout: 15_000
	});
	await expect(splash.getByRole('button', { name: /Код ошибки GB-106/ })).toBeVisible();
	await expect(splash.getByText(/не загрузились файлы интерфейса/)).toBeVisible();

	// Связь вернулась — «Сбросить кэш и открыть» открывает сайт.
	await page.unroute('**/_app/immutable/**');
	await splash.getByRole('button', { name: 'Сбросить кэш и открыть' }).click();
	await expect(page.getByLabel('Имя пользователя')).toBeVisible({ timeout: 15_000 });
	await expect(page.locator('#splash')).toHaveCount(0);
});
