import { expect, test } from '@playwright/test';
import { ADMIN, login, watchConsole } from './helpers';

test('первые шаги, QR-приглашение и помощь для новичков', async ({ page, browser }) => {
	const errors = watchConsole(page);
	await login(page, ADMIN);
	const steps = page.getByRole('region', { name: 'Первые шаги' });
	await expect(steps).toBeVisible();
	await expect(steps.getByText(/Готово 3 из 5/)).toBeVisible();
	await page.screenshot({ path: 'test-results/shots/first-steps-desktop.png' });
	await steps.getByRole('button', { name: 'Скрыть' }).click();
	await expect(steps).toBeHidden();
	await page.reload();
	await expect(page.getByRole('heading', { level: 1 })).toContainText('Привет');
	await expect(page.getByRole('region', { name: 'Первые шаги' })).toHaveCount(0);

	await page.goto('/settings?tab=invites');
	await page.getByRole('button', { name: 'Создать ссылку' }).click();
	await expect(page.getByRole('img', { name: 'QR-код приглашения' })).toBeVisible();
	const link = await page.locator('.fresh .link').textContent();
	await page.getByRole('button', { name: 'QR на весь экран' }).click();
	const screen = page.getByRole('dialog', { name: 'Вступайте в БИН2509' });
	await expect(screen).toBeVisible();
	await page.screenshot({ path: 'test-results/shots/invite-qr-fullscreen.png' });
	await page.keyboard.press('Escape');
	await expect(screen).toBeHidden();

	await page.goto('/install');
	await page.getByRole('button', { name: 'iPhone' }).click();
	await expect(page.getByText('Нажмите «Поделиться»')).toBeVisible();

	const ctx = await browser.newContext({ locale: 'ru-RU', viewport: { width: 390, height: 844 } });
	const guest = await ctx.newPage();
	await guest.goto(link!.trim());
	await guest.getByLabel('ФИО').fill('Петров Иван Сергеевич');
	await expect(guest.getByLabel('Имя пользователя для входа')).toHaveValue('petrov.ivan');
	await guest.goto('/login');
	await guest.getByRole('button', { name: 'Забыли пароль или логин?' }).click();
	await expect(guest.getByText(/Пароль восстанавливает/)).toBeVisible();
	await ctx.close();
	expect(errors).toEqual([]);
});
