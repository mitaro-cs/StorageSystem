import { expect, test } from '@playwright/test';
import { ADMIN, login, watchConsole } from './helpers';

// Подтверждения и вопросы — окном приложения, а не confirm()/prompt() браузера: в окне приложения
// хоста на Mac их нет вовсе, а плагин Tauri подменял confirm() и удаление шло без вопроса.
test('папку создают, переименовывают и удаляют через окно приложения', async ({ page }) => {
	const errors = watchConsole(page);
	let native = 0;
	page.on('dialog', (d) => {
		native++;
		d.dismiss();
	});
	await login(page, ADMIN);
	await page.goto('/subjects/1?tab=materials');

	await page.getByRole('button', { name: 'Новая папка' }).click();
	const ask = page.getByRole('dialog', { name: 'Новая папка' });
	await ask.getByRole('textbox').fill('Контрольные');
	await ask.getByRole('textbox').press('Enter');
	await expect(ask).toBeHidden();
	const folder = page.locator('.folder', { hasText: 'Контрольные' });
	await expect(folder).toBeVisible();

	await folder.getByRole('button', { name: 'Действия' }).click();
	await page.getByRole('menuitem', { name: 'Переименовать' }).click();
	const rename = page.getByRole('dialog', { name: 'Переименовать' });
	await expect(rename.getByRole('textbox')).toHaveValue('Контрольные');
	await rename.getByRole('textbox').fill('Контрольные 2025');
	await rename.getByRole('button', { name: 'Сохранить' }).click();
	const renamed = page.locator('.folder', { hasText: 'Контрольные 2025' });
	await expect(renamed).toBeVisible();

	// «Отмена» — ничего не удаляется; «Удалить» — удаляется.
	await renamed.getByRole('button', { name: 'Действия' }).click();
	await page.getByRole('menuitem', { name: 'Удалить' }).click();
	const confirmBox = page.getByRole('dialog', { name: 'Подтвердите' });
	await expect(confirmBox).toContainText('Удалить папку «Контрольные 2025»');
	await confirmBox.getByRole('button', { name: 'Отмена' }).click();
	await expect(confirmBox).toBeHidden();
	await expect(renamed).toBeVisible();

	await renamed.getByRole('button', { name: 'Действия' }).click();
	await page.getByRole('menuitem', { name: 'Удалить' }).click();
	await page
		.getByRole('dialog', { name: 'Подтвердите' })
		.getByRole('button', { name: 'Удалить' })
		.click();
	await expect(renamed).toBeHidden();

	expect(native, 'системных окон быть не должно').toBe(0);
	expect(errors).toEqual([]);
});

test('логотип: значок во вкладке, картинка для страниц и страница ошибки', async ({ page }) => {
	for (const path of ['/logo.svg', '/favicon.svg']) {
		const r = await page.request.get(path);
		expect(r.status(), path).toBe(200);
		expect(r.headers()['content-type']).toContain('image/svg+xml');
		expect(await r.text()).toContain('id="figure"');
	}
	await page.goto('/такой-страницы-нет');
	await expect(page.getByRole('heading', { name: 'Такой страницы нет' })).toBeVisible();
	// Фигура и сетка глобуса берутся из /logo.svg — картинка должна дорисоваться.
	const figure = page.locator('main.err use[href="/logo.svg#figure"]');
	await expect(figure).toHaveCount(1);
	await expect.poll(async () => (await figure.boundingBox())?.width ?? 0).toBeGreaterThan(10);
});
