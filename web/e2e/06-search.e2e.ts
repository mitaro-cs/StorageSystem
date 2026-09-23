import { expect, test } from '@playwright/test';
import { ADMIN, login, watchConsole } from './helpers';

test('поиск находит по другой форме слова и фильтрует по разделам', async ({ page }) => {
	const errors = watchConsole(page);
	await login(page, ADMIN);
	await page.goto('/search');
	const input = page.getByLabel('Что найти');
	await input.fill('расчеты');
	await expect(page.getByRole('link', { name: 'Типовой расчёт №1' })).toBeVisible();
	await expect(page).toHaveURL(/\/search\?q=/);

	await page.getByRole('button', { name: 'Новости' }).click();
	await expect(page.getByRole('link', { name: 'Типовой расчёт №1' })).toHaveCount(0);
	await input.fill('пары');
	await expect(page.getByRole('link', { name: 'Перенос пары в четверг' })).toBeVisible();
	await page.screenshot({ path: 'test-results/shots/search-desktop.png', fullPage: true });

	await page.getByRole('link', { name: 'Перенос пары в четверг' }).click();
	await expect(page).toHaveURL(/\/news\/\d+$/);
	expect(errors).toEqual([]);
});
