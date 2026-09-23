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
	await page.locator('input[type=file]').setInputFiles({
		name: 'Лекция 1.pdf',
		mimeType: 'application/pdf',
		buffer: pdf
	});
	await expect(page.getByText('Лекция 1.pdf')).toBeVisible();
	await page.getByRole('dialog').getByRole('button', { name: 'Добавить' }).click();
	await expect(page.getByRole('link', { name: 'Лекция 1.pdf' })).toBeVisible();
	await page.screenshot({ path: 'test-results/shots/materials-desktop.png', fullPage: true });
	expect(errors).toEqual([]);

	const ctx = await browser.newContext({ locale: 'ru-RU', viewport: { width: 390, height: 844 } });
	const student = await ctx.newPage();
	await login(student, STUDENT);
	await student.getByRole('link', { name: 'Материалы' }).click();
	await student.getByRole('link', { name: 'Лекция 1.pdf' }).click();
	await expect(student.getByRole('heading', { name: 'Лекция 1.pdf' })).toBeVisible();
	const href = await student.getByRole('link', { name: 'Скачать' }).getAttribute('href');
	const res = await student.request.get(href!);
	expect(res.status()).toBe(200);
	expect((await res.body()).subarray(0, 8).toString()).toBe('%PDF-1.4');
	await student.screenshot({ path: 'test-results/shots/material-mobile.png', fullPage: true });
	await ctx.close();
});
