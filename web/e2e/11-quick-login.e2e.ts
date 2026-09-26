import { expect, test } from '@playwright/test';
import { STUDENT, login, watchConsole } from './helpers';

test('вход без ввода логина: выбор аккаунта и QR-код с телефона', async ({ browser }) => {
	const phoneCtx = await browser.newContext({
		locale: 'ru-RU',
		viewport: { width: 390, height: 844 }
	});
	const phone = await phoneCtx.newPage();
	const errors = watchConsole(phone);
	await login(phone, STUDENT);

	// Выход и повторный вход: логин не вводим — выбираем себя.
	await phone.goto('/profile?tab=data');
	await phone.getByRole('button', { name: 'Выйти' }).click();
	await expect(phone.getByText('Кто входит?')).toBeVisible();
	await phone.getByRole('button', { name: /^Ким Олег/ }).click();
	await expect(phone.getByRole('heading', { level: 1 })).toHaveText('Здравствуйте, Олег');
	await phone.getByLabel('Пароль', { exact: true }).fill(STUDENT.password);
	await phone.screenshot({ path: 'test-results/shots/login-chooser-mobile.png' });
	await phone.getByRole('button', { name: 'Войти' }).click();
	await expect(phone.getByRole('heading', { level: 1 })).toContainText('Привет');

	// Ноутбук показывает QR, телефон подтверждает — ноутбук входит сам.
	const laptopCtx = await browser.newContext({ locale: 'ru-RU' });
	const laptop = await laptopCtx.newPage();
	await laptop.goto('/login');
	await laptop.getByRole('tab', { name: 'По QR-коду' }).click();
	const box = laptop.locator('[data-code]');
	await expect(box).toBeVisible();
	await laptop.screenshot({ path: 'test-results/shots/login-qr-desktop.png' });
	const code = await box.getAttribute('data-code');
	await phone.goto(`/link/${code}`);
	await expect(phone.getByRole('heading', { name: 'Войти на другом устройстве?' })).toBeVisible();
	await phone.getByRole('button', { name: 'Разрешить вход' }).click();
	await expect(phone.getByRole('heading', { name: 'Готово' })).toBeVisible();
	await expect(laptop.getByRole('heading', { level: 1 })).toContainText('Привет, Олег', {
		timeout: 10_000
	});
	expect(errors).toEqual([]);
	await phoneCtx.close();
	await laptopCtx.close();
});
