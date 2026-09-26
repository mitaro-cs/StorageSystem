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

	// Новому человеку — знакомство с сайтом: листаем до конца.
	const welcome = page.getByRole('dialog', { name: 'Знакомство с groupbase' });
	await expect(
		welcome.getByRole('heading', { name: 'Добро пожаловать в groupbase' })
	).toBeVisible();
	const next = welcome.getByRole('button', { name: 'Далее' });
	while (await next.isVisible()) await next.click();
	await expect(welcome.getByRole('heading', { name: 'Вы помогаете группе' })).toBeVisible();
	await welcome.getByRole('button', { name: 'Начать' }).click();
	await expect(welcome).toBeHidden();
	// Больше само не открывается — и на других устройствах (отметка на сервере).
	await page.reload();
	await expect(page.getByRole('heading', { level: 1 })).toHaveText('Привет, Анна');
	await page.waitForTimeout(1500);
	await expect(welcome).toHaveCount(0);
	expect(errors).toEqual([]);
});
