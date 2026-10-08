import { expect, test, type Page } from '@playwright/test';
import { ADMIN, login, watchConsole } from './helpers';

async function api(page: Page, method: string, path: string, body?: unknown) {
	return page.evaluate(
		async ([m, p, b]) => {
			const csrf =
				document.cookie
					.split('; ')
					.find((c) => /^(__Host-)?gb_csrf=/.test(c))
					?.split('=')[1] ?? '';
			const r = await fetch(p as string, {
				method: m as string,
				headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf },
				body: b === undefined ? undefined : JSON.stringify(b)
			});
			return { status: r.status, body: await r.json().catch(() => null) };
		},
		[method, path, body] as const
	);
}

test('архив семестра: собрать, увидеть по семестрам, вернуть предметы', async ({ page }) => {
	const errors = watchConsole(page);
	await login(page, ADMIN);
	const me = await api(page, 'GET', '/api/me');
	const group = me.body.groups[0].id as number;
	const name = `Теория цепей ${Date.now() % 100000}`;
	const created = await api(page, 'POST', `/api/groups/${group}/subjects`, { name });
	expect(created.status).toBe(200);

	await page.goto('/settings?tab=semester');
	await page.getByRole('button', { name: 'Создать', exact: true }).click();
	const dialog = page.getByRole('dialog', { name: 'Архив семестра' });
	await dialog.getByLabel('Название').fill('Осень e2e');
	await dialog.getByRole('checkbox', { name }).check();
	await dialog.getByRole('button', { name: /В архив: 1 предмет/ }).click();
	await expect(dialog).toBeHidden();
	await expect(page.getByRole('link', { name: 'Осень e2e' })).toBeVisible();

	await page.getByRole('link', { name: 'Осень e2e' }).click();
	const section = page.getByRole('region', { name: 'Осень e2e' });
	await expect(section).toContainText(name);

	await section.getByRole('button', { name: 'Действия с архивом' }).click();
	await page.getByRole('menuitem', { name: 'Вернуть предметы' }).click();
	await page.getByRole('dialog').getByRole('button', { name: 'Вернуть' }).click();
	await expect(section).toBeHidden();
	await expect(page.locator('main .grid').first()).toContainText(name);
	expect(errors).toEqual([]);
});
