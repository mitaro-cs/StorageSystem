import { expect, test } from '@playwright/test';
import { ADMIN, STUDENT, login, watchConsole } from './helpers';

test('телефон: внизу – те же разделы, что на компьютере, поиск – в верхней строке', async ({
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
	for (const name of ['Сегодня', 'Новости', 'ДЗ', 'Расписание', 'Предметы', 'Профиль'])
		await expect(nav.getByRole('link', { name })).toBeVisible();
	// Расписание – в одно нажатие, с полосой дней недели.
	await nav.getByRole('link', { name: 'Расписание' }).click();
	await expect(page.getByRole('heading', { level: 1 })).toHaveText('Расписание');
	await expect(page.getByRole('group', { name: 'Дни недели' }).getByRole('button')).toHaveCount(7);
	// Файлы – внутри предметов, как в боковой панели компьютера.
	await expect(nav.getByRole('link', { name: 'Файлы' })).toHaveCount(0);

	await nav.getByRole('link', { name: 'Новости' }).click();
	await expect(page.getByRole('heading', { level: 1 })).toHaveText('Новости');
	await page.getByRole('link', { name: 'Поиск', exact: true }).click();
	await expect(page.getByRole('heading', { level: 1 })).toHaveText('Поиск');

	// Остальное – в профиле; в его «Приложении» видно, какая версия сайта.
	await nav.getByRole('link', { name: 'Профиль' }).click();
	const more = page.getByRole('navigation', { name: 'Разделы' });
	await expect(more.getByRole('link', { name: 'Участники' })).toBeVisible();
	await expect(more.getByRole('link', { name: 'Уведомления' })).toBeVisible();
	await more.getByRole('button', { name: /Приложение/ }).click();
	await expect(page.getByText(/^Campus \S+$/)).toBeVisible();
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
	await expect(page.getByText(/^Campus \S+$/).first()).toBeVisible();

	// На тестовом сервере проверка выключена (GROUPBASE_UPDATE_CHECK=false) – панель так и говорит.
	await page.getByRole('button', { name: 'Проверить обновления' }).click();
	await expect(page.getByRole('status').filter({ hasText: 'Не удалось проверить' })).toContainText(
		'update-check = false'
	);

	// Из своих настроек («Приложение») – ссылка сюда рядом с версией.
	await page.goto('/profile?tab=app');
	await page.getByRole('link', { name: 'проверить обновления' }).click();
	await expect(page).toHaveURL(/tab=updates/);
	await expect(page.getByText('Установлена версия')).toBeVisible();

	// Крупный масштаб интерфейса (0.9.7): плитки и меню «Управления» не вылезают за окно.
	await page.evaluate(() => localStorage.setItem('gb-ui-scale', '150'));
	await page.goto('/settings');
	await expect(page.locator('.tiles')).toBeVisible();
	await expect(page.locator('#splash:not(.gone)')).toHaveCount(0);
	await page.waitForTimeout(600);
	await page.screenshot({ path: 'test-results/settings-150.png' });
	const over = await page.evaluate(
		() => document.documentElement.scrollWidth - document.documentElement.clientWidth
	);
	expect(over).toBeLessThanOrEqual(1);
	await page.evaluate(() => localStorage.removeItem('gb-ui-scale'));
	expect(errors).toEqual([]);
});

test('тема: кнопка в панели и «Оформление» показывают одно и то же', async ({ page }) => {
	const errors = watchConsole(page);
	await login(page, STUDENT);
	await page.goto('/profile?tab=appearance');
	const sidebar = page.locator('.sidebar');
	await page.getByRole('radio', { name: 'Тёмная' }).click();
	await expect(sidebar.getByRole('button', { name: 'Тема: тёмная' })).toBeVisible();
	await sidebar.getByRole('button', { name: 'Тема: тёмная' }).click();
	await expect(page.getByRole('radio', { name: 'Как в системе' })).toHaveAttribute(
		'aria-checked',
		'true'
	);
	await expect(page.locator('html')).not.toHaveAttribute('data-theme');
	expect(errors).toEqual([]);
});
