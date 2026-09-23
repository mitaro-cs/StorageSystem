import { expect, test } from '@playwright/test';

test('приложение открывается без ошибок CSP', async ({ page }) => {
	const errors: string[] = [];
	page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
	await page.goto('/');
	await expect(page.locator('h1')).toHaveText('groupbase');
	expect(errors).toEqual([]);
});
