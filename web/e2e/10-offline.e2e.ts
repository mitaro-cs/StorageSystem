import { expect, test } from '@playwright/test';
import { STUDENT, login, watchConsole } from './helpers';

test('без интернета: читать, отмечать, комментировать, потом всё уходит на сервер', async ({
	browser
}) => {
	test.setTimeout(90_000);
	const ctx = await browser.newContext({ locale: 'ru-RU', timezoneId: 'Europe/Moscow' });
	const page = await ctx.newPage();
	const errors = watchConsole(page);
	await login(page, STUDENT);

	// Дождаться копии на устройстве и service worker, который отдаёт приложение без сети.
	await page.goto('/profile#offline');
	await expect(page.locator('#offline').getByText('ещё ни разу')).toHaveCount(0, {
		timeout: 15_000
	});
	await page.evaluate(() => navigator.serviceWorker.ready);
	await page.reload();
	await page.evaluate(async () => {
		for (let i = 0; i < 50 && !navigator.serviceWorker.controller; i++)
			await new Promise((r) => setTimeout(r, 100));
	});

	await ctx.setOffline(true);
	await page.getByRole('link', { name: 'Домашние задания' }).click();
	await expect(page.getByText(/Нет сети — показаны сохранённые данные/)).toBeVisible();
	await page.getByRole('link', { name: 'Типовой расчёт №1' }).first().click();
	await expect(page.getByRole('heading', { level: 1 })).toHaveText('Типовой расчёт №1');
	const hwId = Number(page.url().split('/').pop());

	// Отметка и комментарий без сети — сразу видны и ждут отправки.
	const cta = page.getByRole('button', { name: /Отметить выполненным|Вернуть в работу/ });
	const wasDone = (await cta.textContent())?.includes('Вернуть');
	await cta.click();
	await page.getByPlaceholder('Написать комментарий…').fill('Сделал без интернета');
	await page.getByRole('button', { name: 'Отправить' }).click();
	await expect(page.getByText('Сделал без интернета')).toBeVisible();
	await expect(page.getByText('ждёт отправки').first()).toBeVisible();
	await expect(page.getByText(/отправится позже: 2/)).toBeVisible();
	await page.screenshot({ path: 'test-results/shots/offline-homework.png', fullPage: true });

	// Перезагрузка без сети: приложение и данные — с устройства.
	await page.reload();
	await expect(page.getByRole('heading', { level: 1 })).toHaveText('Типовой расчёт №1');
	await expect(page.getByText('Сделал без интернета')).toBeVisible();

	// Файл материала, скачанный заранее, открывается без сети.
	const fileStatus = await page.evaluate(async () => {
		const m = await fetch('/api/materials/recent').then(
			(r) => r.json(),
			() => []
		);
		const cache = await caches.open('files-v1');
		const keys = await cache.keys();
		if (!keys.length) return { saved: 0, status: 0, m: m.length };
		const res = await fetch(new URL(keys[0].url).pathname);
		return { saved: keys.length, status: res.status };
	});
	expect(fileStatus.saved).toBeGreaterThan(0);
	expect(fileStatus.status).toBe(200);

	// Сеть вернулась — очередь уходит на сервер по порядку.
	await ctx.setOffline(false);
	await page.evaluate(() => dispatchEvent(new Event('online')));
	await expect(page.getByText(/Отправлено из очереди: 2/)).toBeVisible({ timeout: 15_000 });
	const server = await page.evaluate(async (id) => {
		const [h, c] = await Promise.all([
			fetch(`/api/homework/${id}`).then((r) => r.json()),
			fetch(`/api/homework/${id}/comments`).then((r) => r.json())
		]);
		return { done: h.done, comments: c.map((x: { bodyHtml: string }) => x.bodyHtml).join() };
	}, hwId);
	expect(server.done).toBe(!wasDone);
	expect(server.comments).toContain('Сделал без интернета');
	await expect(page.getByText('ждёт отправки')).toHaveCount(0, { timeout: 10_000 });
	expect(
		errors.filter((e) => !/Failed to load resource|ERR_INTERNET_DISCONNECTED/.test(e))
	).toEqual([]);
	await ctx.close();
});
