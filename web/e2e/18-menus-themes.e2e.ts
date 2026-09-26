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

test('обычному участнику «Управление» не нужно; свои настройки — у шестерёнки, по разделам', async ({
	page
}) => {
	await login(page, STUDENT);
	const side = page.getByRole('complementary', { name: 'Навигация' });
	await expect(side.getByRole('link', { name: 'Сегодня' })).toBeVisible();
	await expect(side.getByRole('link', { name: 'Управление' })).toHaveCount(0);
	// Режим управления переключает только хост.
	await expect(page.getByRole('switch', { name: 'Режим управления' })).toHaveCount(0);
	await side.getByRole('link', { name: 'Настройки' }).click();
	await expect(page).toHaveURL(/\/profile$/);
	// Аккаунт (ФИО, вход, резервные коды) — отдельно от настроек приложения.
	const menu = page.getByRole('navigation', { name: 'Разделы настроек' });
	await expect(menu.getByRole('heading', { name: 'Аккаунт' })).toBeVisible();
	await expect(menu.getByRole('heading', { name: 'Приложение' })).toBeVisible();
	await menu.getByRole('button', { name: /Оформление/ }).click();
	await expect(page.getByRole('heading', { name: 'Оформление' })).toBeVisible();
	await expect(page).toHaveURL(/tab=appearance/);
});

test('цвет оформления: основной цвет и насыщенность меняются сразу и сохраняются', async ({
	page
}) => {
	await login(page, STUDENT);
	await page.goto('/profile?tab=appearance');
	const colors = page.getByRole('radiogroup', { name: 'Цвет' });
	const html = page.locator('html');
	const accent = () =>
		page.evaluate(() =>
			getComputedStyle(document.documentElement).getPropertyValue('--accent').trim()
		);
	await colors.getByRole('radio', { name: 'Синий' }).click();
	await expect(html).toHaveAttribute('data-accent', '');
	const light = await accent();
	expect(light).toMatch(/^#[0-9a-f]{6}$/);
	expect(light).not.toBe('#0d0d0f');

	await page.getByRole('radio', { name: 'Тёмная' }).click();
	await expect(html).toHaveAttribute('data-theme', 'dark');
	const dark = await accent();
	expect(dark).not.toBe(light);

	// Насыщенность до нуля — спокойный серый вместо яркого цвета.
	await page.getByRole('slider', { name: 'Насыщенность' }).fill('0');
	const calm = await accent();
	expect(calm).not.toBe(dark);

	// После перезагрузки — то же, без мигания «Чернилами» (скрипт в app.html).
	await page.reload();
	await expect(html).toHaveAttribute('data-theme', 'dark');
	await expect(html).toHaveAttribute('data-accent', '');
	expect(await accent()).toBe(calm);
	await expect(colors.getByRole('radio', { name: 'Синий' })).toHaveAttribute(
		'aria-checked',
		'true'
	);

	await colors.getByRole('radio', { name: 'Чернила' }).click();
	expect(await html.getAttribute('data-accent')).toBeNull();
	await page.getByRole('radio', { name: 'Как в системе' }).click();
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
