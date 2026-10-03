import { expect, test } from '@playwright/test';
import { ADMIN, watchConsole } from './helpers';

test('первый запуск создаёт группу и администратора', async ({ page }) => {
	const errors = watchConsole(page);
	await page.goto('/');
	await expect(page).toHaveURL(/\/setup$/);
	await page.getByLabel('Код настройки').fill('e2e-setup-code');
	await page.getByLabel('Группа', { exact: true }).fill('БИН2509');
	await page.getByLabel('ФИО').fill(ADMIN.name);
	await page.getByLabel('Имя пользователя для входа').fill(ADMIN.username);
	await page.getByLabel('Пароль', { exact: true }).fill(ADMIN.password);
	await page.getByLabel('Повторите пароль').fill(ADMIN.password);
	await page.getByRole('button', { name: 'Создать' }).click();
	await expect(page.getByRole('heading', { level: 1 })).toHaveText('Привет, Анна');

	// Новичку — тур по сайту: приветствие, подсказки на настоящих кнопках с мини-записями, в конце —
	// большой экран «Добро пожаловать!».
	const welcome = page.getByRole('dialog', { name: 'Знакомство с Campus' });
	await expect(welcome.getByRole('heading', { name: 'Привет, Анна!' })).toBeVisible();
	await welcome.getByRole('button', { name: 'Поехали' }).click();
	await expect(welcome.getByRole('heading', { name: 'Всё главное — на «Сегодня»' })).toBeVisible();
	await page.waitForTimeout(1200);
	await page.screenshot({ path: 'test-results/shots/tour-desktop.png' });
	const next = welcome.getByRole('button', { name: 'Дальше' });
	while (await next.isVisible()) await next.click();
	// У администратора — и «Управление», и «Модерация».
	await expect(welcome.getByRole('heading', { name: 'Модерация' })).toBeVisible();
	await welcome.getByRole('button', { name: 'Готово' }).click();
	await expect(welcome.getByRole('heading', { name: 'Добро пожаловать!' })).toBeVisible();
	await page.waitForTimeout(1800);
	await page.screenshot({ path: 'test-results/shots/tour-finale.png' });
	await welcome.getByRole('button', { name: 'К заданиям' }).click();
	await expect(welcome).toBeHidden();
	// Больше само не открывается — и на других устройствах (отметка на сервере).
	await page.reload();
	await expect(page.getByRole('heading', { level: 1 })).toHaveText('Привет, Анна');
	await page.waitForTimeout(1500);
	await expect(welcome).toHaveCount(0);
	expect(errors).toEqual([]);
});
