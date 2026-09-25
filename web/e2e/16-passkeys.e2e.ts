import { expect, test } from '@playwright/test';
import { STUDENT, watchConsole } from './helpers';

test('вход по ключу: добавляется в профиле, потом вход без пароля — подсказкой и кнопкой', async ({
	page,
	context
}) => {
	const errors = watchConsole(page);
	// WebAuthn не работает по IP — открываем сайт как localhost.
	const site = new URL(test.info().project.use.baseURL!);
	site.hostname = 'localhost';
	const at = (path: string) => new URL(path, site).toString();

	// Виртуальный ключ Chromium: как Touch ID — с подтверждением человека и хранением на устройстве.
	const cdp = await context.newCDPSession(page);
	await cdp.send('WebAuthn.enable');
	await cdp.send('WebAuthn.addVirtualAuthenticator', {
		options: {
			protocol: 'ctap2',
			transport: 'internal',
			hasResidentKey: true,
			hasUserVerification: true,
			isUserVerified: true,
			automaticPresenceSimulation: true
		}
	});

	await page.goto(at('/login'));
	await page.getByLabel('Имя пользователя').fill(STUDENT.username);
	await page.getByLabel('Пароль', { exact: true }).fill(STUDENT.password);
	await page.getByRole('button', { name: 'Войти', exact: true }).click();
	await expect(page.getByRole('heading', { level: 1 })).toContainText('Привет');

	await page.goto(at('/profile'));
	await page.getByRole('button', { name: 'Добавить ключ' }).click();
	const dialog = page.getByRole('dialog', { name: 'Вход по отпечатку или лицу' });
	await dialog.getByLabel('Пароль').fill(STUDENT.password);
	await dialog.getByRole('button', { name: 'Добавить' }).click();
	await expect(dialog).toBeHidden({ timeout: 20_000 });
	await expect(page.getByText(/^добавлен /)).toBeVisible();

	// Выходим совсем (без куки): ключ подсказывается прямо в поле логина, как сохранённый пароль, —
	// выбрали и вошли. Виртуальный ключ Chromium подтверждает выбор сам.
	await context.clearCookies();
	await page.goto(at('/login'));
	await expect(page.getByRole('heading', { level: 1 })).toContainText('Привет', {
		timeout: 20_000
	});

	// Кнопкой — где браузер не умеет подсказывать ключ в поле.
	await page.addInitScript(() => {
		Object.defineProperty(PublicKeyCredential, 'isConditionalMediationAvailable', {
			value: async () => false
		});
	});
	await context.clearCookies();
	await page.goto(at('/login'));
	await page.getByRole('button', { name: 'Войти по отпечатку или лицу' }).click();
	await expect(page.getByRole('heading', { level: 1 })).toContainText('Привет', {
		timeout: 20_000
	});
	await page.goto(at('/profile'));
	await expect(page.getByText(/вход .*назад|вход только что/)).toBeVisible();
	expect(errors).toEqual([]);
});
