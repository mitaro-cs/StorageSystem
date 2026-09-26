import { expect, test } from '@playwright/test';
import { ADMIN, STUDENT, login, watchConsole } from './helpers';

test('телефон: внизу — те же разделы, что на компьютере, поиск — в верхней строке', async ({
	browser
}) => {
	const ctx = await browser.newContext({
		locale: 'ru-RU',
		viewport: { width: 390, height: 844 },
		isMobile: true,
		hasTouch: true
	});
	const page = await ctx.newPage();
	const errors = watchConsole(page);
	await login(page, STUDENT);
	const nav = page.getByRole('navigation', { name: 'Основные разделы' });
	for (const name of ['Сегодня', 'Новости', 'ДЗ', 'Предметы', 'Профиль'])
		await expect(nav.getByRole('link', { name })).toBeVisible();
	// Файлы — внутри предметов, как в боковой панели компьютера.
	await expect(nav.getByRole('link', { name: 'Файлы' })).toHaveCount(0);

	await nav.getByRole('link', { name: 'Новости' }).click();
	await expect(page.getByRole('heading', { level: 1 })).toHaveText('Новости');
	await page.getByRole('link', { name: 'Поиск', exact: true }).click();
	await expect(page.getByRole('heading', { level: 1 })).toHaveText('Поиск');

	// Остальное — в профиле; в его «Приложении» видно, какая версия сайта.
	await nav.getByRole('link', { name: 'Профиль' }).click();
	const more = page.getByRole('navigation', { name: 'Разделы' });
	await expect(more.getByRole('link', { name: 'Участники' })).toBeVisible();
	await expect(more.getByRole('link', { name: 'Уведомления' })).toBeVisible();
	await more.getByRole('button', { name: /Приложение/ }).click();
	await expect(page.getByText(/^groupbase \S+$/)).toBeVisible();
	expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBe(0);
	expect(errors).toEqual([]);
	await ctx.close();
});

test('настройки: какая версия стоит и кнопка «Проверить обновления»', async ({ page }) => {
	const errors = watchConsole(page);
	await login(page, ADMIN);
	await page.goto('/settings');
	const item = page.getByRole('button', { name: /Версия и обновления/ });
	await expect(item).toContainText(/Сейчас \S+/);
	await item.click();
	await expect(page.getByText('Установлена версия')).toBeVisible();
	await expect(page.getByText(/^groupbase \S+$/).first()).toBeVisible();

	// На тестовом сервере проверка выключена (GROUPBASE_UPDATE_CHECK=false) — панель так и говорит.
	await page.getByRole('button', { name: 'Проверить обновления' }).click();
	await expect(page.getByRole('status').filter({ hasText: 'Не удалось проверить' })).toContainText(
		'update-check = false'
	);

	// Из своих настроек («Приложение») — ссылка сюда рядом с версией.
	await page.goto('/profile?tab=app');
	await page.getByRole('link', { name: 'проверить обновления' }).click();
	await expect(page).toHaveURL(/tab=updates/);
	await expect(page.getByText('Установлена версия')).toBeVisible();
	expect(errors).toEqual([]);
});
