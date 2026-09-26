import { expect, test } from '@playwright/test';
import { ADMIN, STUDENT, login, watchConsole } from './helpers';

test('боковая панель: файлы — в предметах, уведомления — колокольчиком на «Сегодня»', async ({
	page
}) => {
	const errors = watchConsole(page);
	await login(page, ADMIN);
	const side = page.getByRole('complementary', { name: 'Навигация' });
	await expect(side.getByRole('link', { name: 'Предметы' })).toBeVisible();
	await expect(side.getByRole('link', { name: 'Файлы' })).toHaveCount(0);
	await expect(side.getByRole('link', { name: 'Уведомления' })).toHaveCount(0);

	await page
		.getByRole('main')
		.getByRole('link', { name: /^Уведомления/ })
		.click();
	await expect(page.getByRole('heading', { level: 1 })).toHaveText('Уведомления');
	// Прежние адреса и закладки работают.
	await page.goto('/materials');
	await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
	expect(errors).toEqual([]);
});

test('кнопку «Сессия» в меню староста показывает, когда она нужна', async ({ page }) => {
	await login(page, ADMIN);
	await page.goto('/settings?tab=semester');
	const side = page.getByRole('complementary', { name: 'Навигация' });
	const pick = page.getByRole('radiogroup', { name: 'Кнопка «Сессия» в меню' });

	await pick.getByRole('radio', { name: 'Не показывать' }).click();
	await expect(side.getByRole('link', { name: 'Сессия' })).toHaveCount(0);
	await pick.getByRole('radio', { name: 'Всегда' }).click();
	await expect(side.getByRole('link', { name: 'Сессия' })).toBeVisible();
	// Страница открывается и без кнопки — по ссылке.
	await pick.getByRole('radio', { name: 'Около сессии' }).click();
	await expect(pick.getByRole('radio', { name: 'Около сессии' })).toHaveAttribute(
		'aria-checked',
		'true'
	);
	await page.goto('/session');
	await expect(page.getByRole('heading', { name: 'Сессия', level: 1 })).toBeVisible();
});

test('модератор сайта видит участников списком: блокирует и исключает только админ и староста', async ({
	page,
	browser
}) => {
	await login(page, ADMIN);
	await page.goto('/members');
	const row = page.locator('.list-row', { hasText: STUDENT.name });
	await row.getByRole('button', { name: 'Действия' }).click();
	await page.getByRole('menuitem', { name: 'Сделать модератором' }).click();
	await expect(row).toContainText('Модератор');

	const ctx = await browser.newContext({ locale: 'ru-RU' });
	const moderator = await ctx.newPage();
	await login(moderator, STUDENT);
	await moderator.goto('/members');
	await expect(moderator.locator('.list-row', { hasText: ADMIN.name })).toBeVisible();
	await expect(moderator.getByRole('button', { name: 'Действия' })).toHaveCount(0);
	// Модератору — «Модерация», но не «Управление» и не переключатель режима управления.
	const side = moderator.getByRole('complementary', { name: 'Навигация' });
	await expect(side.getByRole('link', { name: 'Модерация' })).toBeVisible();
	await expect(side.getByRole('link', { name: 'Управление' })).toHaveCount(0);
	await expect(moderator.getByRole('switch', { name: 'Режим управления' })).toHaveCount(0);
	await expect(side.getByRole('link', { name: 'Настройки' })).toBeVisible();
	await ctx.close();

	await row.getByRole('button', { name: 'Действия' }).click();
	await page.getByRole('menuitem', { name: 'Снять роль модератора' }).click();
	await expect(row).not.toContainText('Модератор');
});

test('дизайн оформления: пять вариантов, сразу меняет вид и запоминается', async ({ page }) => {
	await login(page, STUDENT);
	await page.goto('/profile?tab=appearance');
	const html = page.locator('html');
	const designs = page.getByRole('radiogroup', { name: 'Дизайн' });
	await expect(designs.getByRole('radio')).toHaveCount(5);
	await designs.getByRole('radio', { name: 'Стекло' }).click();
	await expect(html).toHaveAttribute('data-style', 'glass');
	const blur = () =>
		page.evaluate(
			() => getComputedStyle(document.querySelector('.card')!).backdropFilter || 'none'
		);
	expect(await blur()).toContain('blur');

	await page.reload();
	await expect(html).toHaveAttribute('data-style', 'glass');
	await designs.getByRole('radio', { name: 'Бумага' }).click();
	await expect(html).toHaveAttribute('data-style', 'paper');
	// «Бумага» — заголовки с засечками.
	const font = await page.evaluate(
		() => getComputedStyle(document.querySelector('h1')!).fontFamily
	);
	expect(font).toMatch(/Iowan|Palatino|Georgia/);
	await designs.getByRole('radio', { name: 'Классика' }).click();
	await expect(html).not.toHaveAttribute('data-style', /.+/);
	expect(await blur()).toBe('none');
});
