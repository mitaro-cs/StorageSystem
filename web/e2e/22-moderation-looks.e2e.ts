import { expect, test } from '@playwright/test';
import { ADMIN, STUDENT, login, watchConsole } from './helpers';

test('жалоба студента — в «Модерации»: модератор скрывает новость', async ({ page, browser }) => {
	const errors = watchConsole(page);
	await login(page, ADMIN);
	await page.goto('/news?new=1');
	const dialog = page.getByRole('dialog');
	await dialog.getByLabel('Заголовок').fill('Спорное объявление');
	await dialog.getByRole('button', { name: 'Опубликовать' }).click();
	await expect(dialog).toBeHidden();

	const ctx = await browser.newContext({ locale: 'ru-RU' });
	const student = await ctx.newPage();
	await login(student, STUDENT);
	await student.goto('/news');
	const card = student.locator('article', { hasText: 'Спорное объявление' });
	await card.getByRole('button', { name: 'Действия' }).click();
	await student.getByRole('menuitem', { name: 'Пожаловаться' }).click();
	const ask = student.getByRole('dialog');
	await ask.getByLabel('Что не так').fill('Реклама');
	await ask.getByRole('button', { name: 'Отправить' }).click();
	await expect(student.getByText('Жалоба отправлена модераторам')).toBeVisible();

	// У модератора — «Модерация» с числом, жалоба с пояснением, без имени.
	const side = page.getByRole('complementary', { name: 'Навигация' });
	await page.goto('/moderation');
	await expect(side.getByRole('link', { name: /Модерация/ })).toBeVisible();
	const report = page.locator('article', { hasText: 'Спорное объявление' });
	await expect(report.getByText('Реклама')).toBeVisible();
	await expect(report).not.toContainText(STUDENT.name);
	await report.getByRole('button', { name: 'Скрыть' }).click();
	await expect(page.getByText('Скрыто', { exact: true })).toBeVisible();
	await page.getByRole('link', { name: 'Скрытое' }).click();
	await expect(page.locator('article', { hasText: 'Спорное объявление' })).toBeVisible();

	// Студент новости больше не видит.
	await student.goto('/news');
	await expect(student.locator('article', { hasText: 'Спорное объявление' })).toHaveCount(0);
	await ctx.close();
	expect(errors).toEqual([]);
});

test('новый комментарий появляется у других сразу, без перезагрузки', async ({ page, browser }) => {
	await login(page, STUDENT);
	await page.goto('/news');
	await page.locator('article h3 a').first().click();
	await expect(page.getByRole('heading', { name: /Комментарии/ })).toBeVisible();
	const url = page.url();
	expect(url).toMatch(/\/news\/\d+$/);

	const ctx = await browser.newContext({ locale: 'ru-RU' });
	const admin = await ctx.newPage();
	await login(admin, ADMIN);
	await admin.goto(url);
	await admin.getByPlaceholder('Написать комментарий…').fill('Живой комментарий');
	await admin.getByRole('button', { name: 'Отправить' }).click();
	await expect(admin.getByText('Живой комментарий')).toBeVisible();

	// Студент ничего не перезагружал.
	await expect(page.getByText('Живой комментарий')).toBeVisible({ timeout: 10_000 });
	await ctx.close();
});

test('оформление: новые стили, фон и значок меняются сразу и запоминаются', async ({ page }) => {
	const errors = watchConsole(page);
	await login(page, STUDENT);
	await page.goto('/profile');
	const html = page.locator('html');
	await page
		.getByRole('radiogroup', { name: 'Стиль' })
		.getByRole('radio', { name: 'Неон' })
		.click();
	await expect(html).toHaveAttribute('data-style', 'neon');
	await page
		.getByRole('radiogroup', { name: 'Фон' })
		.getByRole('radio', { name: 'Клетка' })
		.click();
	await expect(html).toHaveAttribute('data-bg', 'grid');
	await page
		.getByRole('radiogroup', { name: 'Значок' })
		.getByRole('radio', { name: 'Океан' })
		.click();
	await expect(page.locator('link[rel="icon"]')).toHaveAttribute('href', /icons\/v\/ocean\.svg/);
	await expect(page.locator('link[rel="manifest"]')).toHaveAttribute(
		'href',
		'/manifest-ocean.webmanifest'
	);
	// Оформление под значок: интерфейс в его цветах.
	await expect(html).toHaveAttribute('data-palette', 'ocean');
	await expect(
		page.getByRole('radiogroup', { name: 'Цвет' }).getByRole('radio', { name: 'Океан' })
	).toHaveAttribute('aria-checked', 'true');

	// После перезагрузки — то же, ещё до отрисовки интерфейса (скрипт в app.html).
	await page.reload();
	await expect(html).toHaveAttribute('data-style', 'neon');
	await expect(html).toHaveAttribute('data-bg', 'grid');
	await expect(page.locator('link[rel="icon"]')).toHaveAttribute('href', /icons\/v\/ocean\.svg/);
	const manifest = await page.request.get('/manifest-ocean.webmanifest');
	expect(await manifest.text()).toContain('/icons/v/ocean-192.png');

	// Вернуть как было — для остальных тестов.
	await page
		.getByRole('radiogroup', { name: 'Стиль' })
		.getByRole('radio', { name: 'Обычный' })
		.click();
	await page
		.getByRole('radiogroup', { name: 'Фон' })
		.getByRole('radio', { name: 'Без фона' })
		.click();
	await page
		.getByRole('radiogroup', { name: 'Значок' })
		.getByRole('radio', { name: 'Светлый' })
		.click();
	await expect(html).not.toHaveAttribute('data-bg', /.+/);
	await expect(html).not.toHaveAttribute('data-palette', /.+/);
	expect(errors).toEqual([]);
});
