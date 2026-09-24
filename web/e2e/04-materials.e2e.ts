import { expect, test } from '@playwright/test';
import { ADMIN, STUDENT, login, watchConsole } from './helpers';

const pdf = Buffer.from('%PDF-1.4\n% Конспект: ряды Фурье\n' + 'x'.repeat(2000) + '\n%%EOF\n');

test('староста загружает материал, студент его видит и скачивает', async ({ page, browser }) => {
	const errors = watchConsole(page);
	await login(page, ADMIN);
	await page.goto('/subjects');
	await page
		.getByRole('link', { name: /Математический анализ/ })
		.first()
		.click();
	await page
		.getByRole('navigation', { name: 'Разделы предмета' })
		.getByRole('link', { name: 'Материалы' })
		.click();
	await page.getByRole('button', { name: 'Добавить' }).click();
	// Медленная загрузка (как на слабом компьютере хоста): «Добавить» должна дождаться файла.
	await page.route('**/api/files', async (route) => {
		await new Promise((r) => setTimeout(r, 2000));
		await route.continue();
	});
	await page.locator('input[type=file]').setInputFiles({
		name: 'Лекция 1.pdf',
		mimeType: 'application/pdf',
		buffer: pdf
	});
	// Нажимаем сразу, пока файл ещё грузится: кнопка ждёт загрузку, а не ругается «нет файлов».
	const dialog = page.getByRole('dialog');
	await expect(dialog.getByRole('progressbar')).toBeVisible();
	await dialog.getByRole('button', { name: 'Добавить' }).click();
	await expect(dialog).toBeHidden({ timeout: 20_000 });
	// Первая загрузка на только что запущенном сервере в CI бывает дольше 5 секунд.
	await expect(page.getByRole('link', { name: 'Лекция 1.pdf' }).first()).toBeVisible({
		timeout: 20_000
	});
	await page.screenshot({ path: 'test-results/shots/materials-desktop.png', fullPage: true });
	expect(errors).toEqual([]);

	const ctx = await browser.newContext({ locale: 'ru-RU', viewport: { width: 390, height: 844 } });
	const student = await ctx.newPage();
	await login(student, STUDENT);
	await student
		.getByRole('navigation', { name: 'Основные разделы' })
		.getByRole('link', { name: 'Материалы' })
		.click();
	await student.getByRole('link', { name: 'Лекция 1.pdf' }).first().click();
	await expect(student.getByRole('heading', { name: 'Лекция 1.pdf' })).toBeVisible();
	const href = await student.getByRole('link', { name: 'Скачать' }).getAttribute('href');
	const res = await student.request.get(href!);
	expect(res.status()).toBe(200);
	expect((await res.body()).subarray(0, 8).toString()).toBe('%PDF-1.4');
	await student.screenshot({ path: 'test-results/shots/material-mobile.png', fullPage: true });
	await ctx.close();
});
