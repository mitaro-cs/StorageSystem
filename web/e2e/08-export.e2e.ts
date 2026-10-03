import { expect, test } from '@playwright/test';
import { ADMIN, login } from './helpers';

test('староста скачивает архив группы', async ({ page }) => {
	await login(page, ADMIN);
	await page.goto('/settings?tab=export');
	await expect(page.getByRole('heading', { name: /Архив группы/ })).toBeVisible();
	const [group] = await Promise.all([
		page.waitForEvent('download'),
		page.getByRole('link', { name: 'Скачать архив' }).click()
	]);
	expect(group.suggestedFilename()).toMatch(/^БИН2509-архив-\d{4}-\d{2}-\d{2}\.zip$/);
	await page.screenshot({ path: 'test-results/shots/export-desktop.png', fullPage: true });
	// «Мои данные» убраны из профиля (0.7, просьба владельца).
	await page.goto('/profile');
	await expect(page.getByRole('button', { name: /Мои данные/ })).toHaveCount(0);
});
