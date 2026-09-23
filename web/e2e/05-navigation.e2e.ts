import { expect, test } from '@playwright/test';
import { ADMIN, login, watchConsole } from './helpers';

test('палитра Ctrl+K и горячие клавиши', async ({ page }) => {
	const errors = watchConsole(page);
	await page.emulateMedia({ colorScheme: 'dark' });
	await login(page, ADMIN);

	await page.keyboard.press('Control+k');
	const input = page.getByRole('combobox');
	await expect(input).toBeFocused();
	await input.fill('анализ');
	await page.screenshot({ path: 'test-results/shots/palette-dark.png', animations: 'disabled' });
	await page.keyboard.press('Enter');
	await expect(page).toHaveURL(/\/subjects\/\d+$/);

	// «Недавнее» появляется в палитре после открытия предмета.
	await page.keyboard.press('Control+k');
	await expect(page.getByRole('option', { name: /Математический анализ/ }).first()).toBeVisible();
	await page.keyboard.press('Escape');
	await expect(page.getByRole('dialog', { name: 'Командная палитра' })).toBeHidden();

	await page.keyboard.press('g');
	await page.keyboard.press('d');
	await expect(page).toHaveURL(/\/homework$/);
	await page.keyboard.press('g');
	await page.keyboard.press('h');
	await expect(page).toHaveURL(/\/$/);

	await page.keyboard.press('n');
	await expect(page.getByRole('dialog', { name: 'Новое задание' })).toBeVisible();
	await page.keyboard.press('Escape');
	await expect(page.getByRole('dialog', { name: 'Новое задание' })).toBeHidden();

	await page.keyboard.press('Shift+?');
	await expect(page.getByRole('heading', { name: 'Горячие клавиши' })).toBeVisible();
	await page.screenshot({ path: 'test-results/shots/help-dark.png', animations: 'disabled' });
	expect(errors).toEqual([]);
});
