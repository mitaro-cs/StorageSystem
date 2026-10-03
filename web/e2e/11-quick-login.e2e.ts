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
	// «Выйти» — внизу меню профиля.
	await phone.goto('/profile');
	await phone.getByRole('button', { name: /^Выйти/ }).click();
	await expect(phone.getByText('Кто входит?')).toBeVisible();
	await phone.getByRole('button', { name: /^Ким Олег/ }).click();
	await expect(phone.getByRole('heading', { level: 1 })).toHaveText('Здравствуйте, Олег');
	await phone.getByLabel('Пароль', { exact: true }).fill(STUDENT.password);
	await phone.screenshot({ path: 'test-results/shots/login-chooser-mobile.png' });
	await phone.getByRole('button', { name: 'Войти' }).click();
	await expect(phone.getByRole('heading', { level: 1 })).toContainText('Привет');

	// Телефон (уже вошли) показывает код; ноутбук открывает QR-ссылку — как камерой — и входит.
	await phone.goto('/profile?tab=security');
	await phone.getByRole('button', { name: 'Показать код' }).click();
	const shown = phone.getByRole('dialog', { name: 'Вход на другом устройстве' });
	const box = shown.locator('[data-code]');
	await expect(box).toBeVisible();
	await phone.screenshot({ path: 'test-results/shots/show-code-mobile.png' });
	const code = await box.getAttribute('data-code');
	const laptopCtx = await browser.newContext({ locale: 'ru-RU' });
	const laptop = await laptopCtx.newPage();
	await laptop.goto(`/enter/${code}`);
	await expect(laptop.getByRole('heading', { level: 1 })).toContainText('Привет, Олег', {
		timeout: 10_000
	});
	// Телефон видит, что вход состоялся.
	await expect(shown.getByText('Готово!')).toBeVisible({ timeout: 10_000 });
	await shown.getByRole('button', { name: 'Закрыть' }).click();

	// Без камеры: 6 цифр вводят на странице входа второго ноутбука — «По коду».
	await phone.getByRole('button', { name: 'Показать код' }).click();
	const pin = (await shown.locator('.pin').innerText()).replace(/\D/g, '');
	expect(pin).toMatch(/^\d{6}$/);
	const otherCtx = await browser.newContext({ locale: 'ru-RU' });
	const other = await otherCtx.newPage();
	await other.goto('/login');
	await other.getByRole('tab', { name: 'По коду' }).click();
	await other.getByLabel('Или 6 цифр').fill(`${pin.slice(0, 3)} ${pin.slice(3)}`);
	await expect(other.getByRole('heading', { level: 1 })).toContainText('Привет, Олег', {
		timeout: 10_000
	});
	expect(errors).toEqual([]);
	await phoneCtx.close();
	await laptopCtx.close();
	await otherCtx.close();
});
