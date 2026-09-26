import { expect, test, type Locator, type Page } from '@playwright/test';
import { ADMIN, STUDENT, login, watchConsole } from './helpers';

/** Меню у кнопки у самого низа экрана: должно открыться целиком (вверх), а не пропасть за краем. */
async function openMenuAtBottom(page: Page, trigger: Locator) {
	await trigger.evaluate((el) => el.scrollIntoView({ block: 'end' }));
	await trigger.click();
	const menu = page.getByRole('menu');
	await expect(menu).toBeVisible();
	const box = (await menu.boundingBox())!;
	const view = page.viewportSize()!;
	expect(box.y).toBeGreaterThanOrEqual(0);
	expect(box.y + box.height).toBeLessThanOrEqual(view.height);
	expect(box.x).toBeGreaterThanOrEqual(0);
	expect(box.x + box.width).toBeLessThanOrEqual(view.width);
	return menu;
}

test('обычному участнику отдельный пункт «Настройки» не нужен', async ({ page }) => {
	await login(page, STUDENT);
	const nav = page.getByRole('navigation').first();
	await expect(nav.getByRole('link', { name: 'Сегодня' })).toBeVisible();
	await expect(nav.getByRole('link', { name: 'Настройки' })).toHaveCount(0);
	await page.goto('/profile');
	await expect(page.getByRole('heading', { name: 'Оформление' })).toBeVisible();
});

test('тема оформления: цвет и режим меняются сразу и сохраняются', async ({ page }) => {
	await login(page, STUDENT);
	await page.goto('/profile');
	await page
		.getByRole('radiogroup', { name: 'Цвет' })
		.getByRole('radio', { name: 'Океан' })
		.click();
	const html = page.locator('html');
	await expect(html).toHaveAttribute('data-palette', 'ocean');
	const accent = () =>
		page.evaluate(() =>
			getComputedStyle(document.documentElement).getPropertyValue('--accent').trim()
		);
	expect(await accent()).toBe('#2f5bea');

	await page.getByRole('radio', { name: 'Тёмная' }).click();
	await expect(html).toHaveAttribute('data-theme', 'dark');
	expect(await accent()).toBe('#7b9cff');

	// После перезагрузки — та же тема, без мигания «Классикой».
	await page.reload();
	await expect(html).toHaveAttribute('data-palette', 'ocean');
	await expect(html).toHaveAttribute('data-theme', 'dark');
	await expect(
		page.getByRole('radiogroup', { name: 'Цвет' }).getByRole('radio', { name: 'Океан' })
	).toHaveAttribute('aria-checked', 'true');

	await page.getByRole('radio', { name: 'Классика' }).click();
	await expect(html).not.toHaveAttribute('data-palette', /.+/);
});

test('меню действий у нижнего края экрана открывается целиком — в участниках и в настройках', async ({
	page
}) => {
	const errors = watchConsole(page);
	await page.setViewportSize({ width: 390, height: 640 });
	await login(page, ADMIN);

	await page.goto('/members');
	const row = page.locator('.list-row', { hasText: STUDENT.name });
	let menu = await openMenuAtBottom(page, row.getByRole('button', { name: 'Действия' }));
	await expect(menu.getByRole('menuitem', { name: 'Сделать замом старосты' })).toBeVisible();
	// Пункт нажимается — меню не обрезано карточкой.
	await menu.getByRole('menuitem', { name: 'Сделать замом старосты' }).click();
	await expect(row).toContainText('Зам старосты');

	await page.goto('/settings?tab=accounts');
	const inSettings = page.locator('.list-row', { hasText: STUDENT.name });
	menu = await openMenuAtBottom(page, inSettings.getByRole('button', { name: 'Действия' }));
	await menu.getByRole('menuitem', { name: 'Сделать студентом' }).click();
	await expect(inSettings).not.toContainText('Зам старосты');
	expect(errors).toEqual([]);
});
