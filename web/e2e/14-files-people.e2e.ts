import { expect, test, type Page } from '@playwright/test';
import { ADMIN, STUDENT, login, watchConsole } from './helpers';

const note = Buffer.from('Лабораторная №3\n\n1. Измерить период маятника.\n2. Построить график.\n');

/** Ничего на странице не шире экрана — иначе телефон «ужимает» её, чтобы всё влезло. */
async function fitsScreen(page: Page) {
	const [scroll, client] = await page.evaluate(() => [
		document.documentElement.scrollWidth,
		document.documentElement.clientWidth
	]);
	expect(scroll).toBeLessThanOrEqual(client);
}

test('задание со сложностью и файлом: файл открывается прямо в приложении', async ({ page }) => {
	const errors = watchConsole(page);
	await login(page, ADMIN);
	await page.getByRole('button', { name: 'Задание', exact: true }).click();
	const dialog = page.getByRole('dialog');
	await dialog.getByLabel('Предмет').selectOption({ label: 'Математический анализ' });
	await dialog.getByLabel('Что сделать').fill('Маятник: отчёт');
	await dialog.getByRole('radio', { name: /Сложно/ }).click();
	await dialog.locator('input[type=file]').first().setInputFiles({
		name: 'Задание.txt',
		mimeType: 'text/plain',
		buffer: note
	});
	// Файл загружен, когда у него появилась кнопка «Убрать» (строка загрузки ещё может исчезать).
	await expect(dialog.getByRole('button', { name: 'Убрать Задание.txt' })).toBeVisible({
		timeout: 20_000
	});
	await dialog.getByRole('button', { name: 'Опубликовать' }).click();
	await expect(dialog).toBeHidden({ timeout: 20_000 });

	await page.goto('/homework');
	await page.getByRole('link', { name: 'Маятник: отчёт' }).click();
	await expect(page.getByText('Сложно').first()).toBeVisible();
	// Путь до задания.
	await expect(page.getByRole('navigation', { name: 'Путь' })).toContainText('Задания');

	await page.getByRole('button', { name: /Открыть Задание.txt/ }).click();
	const viewer = page.getByRole('dialog', { name: /Просмотр/ });
	await expect(viewer.getByText('Измерить период маятника.')).toBeVisible();
	await expect(viewer.getByRole('link', { name: 'Скачать' })).toHaveAttribute(
		'href',
		/download=true/
	);
	await page.keyboard.press('Escape');
	await expect(viewer).toBeHidden();

	// Тот же файл — в разделе «Файлы»: Предмет › Задания › задание.
	await page.goto('/materials');
	await page
		.locator('main a[href^="/materials?subject="]', { hasText: 'Математический анализ' })
		.click();
	await page
		.getByRole('main')
		.getByRole('link', { name: /Задания/ })
		.click();
	await page.getByRole('link', { name: /Маятник: отчёт/ }).click();
	await expect(page.getByRole('navigation', { name: 'Путь' })).toContainText('Маятник: отчёт');
	await page.getByRole('button', { name: /Задание.txt/ }).click();
	await expect(viewer.getByText('Построить график.')).toBeVisible();
	await page.keyboard.press('Escape');
	expect(errors).toEqual([]);
});

test('иконка предмета: подбирается по названию и выбирается вручную', async ({ page }) => {
	const errors = watchConsole(page);
	await login(page, ADMIN);
	await page.goto('/subjects');
	await page
		.getByRole('link', { name: /Математический анализ/ })
		.first()
		.click();
	await page.getByRole('button', { name: 'Действия' }).click();
	await page.getByRole('menuitem', { name: 'Изменить' }).click();
	const dialog = page.getByRole('dialog');
	await expect(dialog.getByText('По названию: Математика')).toBeVisible();
	await dialog.getByRole('button', { name: 'Выбрать' }).click();
	await dialog.getByLabel('Поиск иконки').fill('проект');
	await dialog.getByRole('button', { name: 'Проект' }).click();
	await dialog.getByRole('button', { name: 'Сохранить' }).click();
	await expect(dialog).toBeHidden();
	const saved = await page.evaluate(() => fetch('/api/subjects').then((r) => r.json()));
	expect(saved.find((s: { name: string }) => s.name === 'Математический анализ').icon).toBe(
		'rocket'
	);
	expect(errors).toEqual([]);
});

test('люди: администратор назначает модератора сайта и добавляет человека с QR-кодом', async ({
	page
}) => {
	const errors = watchConsole(page);
	await login(page, ADMIN);
	await page.goto('/members');
	const row = page.locator('.list-row', { hasText: STUDENT.name });
	await row.getByRole('button', { name: 'Действия' }).click();
	await row.getByRole('menuitem', { name: 'Сделать модератором сайта' }).click();
	await expect(row.getByText('Модератор сайта')).toBeVisible();
	await row.getByRole('button', { name: 'Действия' }).click();
	await row.getByRole('menuitem', { name: 'Снять роль модератора' }).click();
	await expect(row.getByText('Модератор сайта')).toHaveCount(0);

	await page.getByRole('button', { name: 'Добавить людей' }).click();
	const dialog = page.getByRole('dialog');
	await dialog.getByLabel('ФИО').fill('Сидорова Мария Петровна');
	await dialog.getByRole('button', { name: 'Добавить и показать QR-код' }).click();
	await expect(dialog.getByText(/Сидорова Мария Петровна/)).toBeVisible();
	await expect(dialog.getByRole('img', { name: 'QR-код активации' })).toBeVisible();
	await dialog.getByRole('button', { name: 'Закрыть' }).click();
	await expect(page.locator('.list-row', { hasText: 'Сидорова Мария Петровна' })).toBeVisible();
	expect(errors).toEqual([]);
});

test('ссылка-приглашение на втором устройстве: «вы уже в группе», а не ошибка', async ({
	page,
	browser
}) => {
	await login(page, ADMIN);
	const inv = await page.evaluate(async () => {
		const me = await fetch('/api/me').then((r) => r.json());
		const csrf = document.cookie.match(/gb_csrf=([^;]+)/)?.[1] ?? '';
		return fetch(`/api/groups/${me.groups[0].id}/invites`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf },
			body: JSON.stringify({ ttlHours: 24 })
		}).then((r) => r.json());
	});

	// Второе устройство студента: без входа предлагают войти, а не регистрироваться заново.
	const ctx = await browser.newContext({ locale: 'ru-RU', viewport: { width: 390, height: 844 } });
	const phone = await ctx.newPage();
	const errors = watchConsole(phone);
	await phone.goto(inv.path);
	await phone.getByRole('tab', { name: /У меня есть аккаунт/ }).click();
	await phone.getByRole('link', { name: /Войти по логину и паролю/ }).click();
	await phone.getByLabel('Имя пользователя').fill(STUDENT.username);
	await phone.getByLabel('Пароль', { exact: true }).fill(STUDENT.password);
	await phone.getByRole('button', { name: 'Войти' }).click();
	await expect(phone.getByText(/вы уже в этой группе/)).toBeVisible();
	await fitsScreen(phone);
	await phone.getByRole('button', { name: 'Открыть группу' }).click();
	await expect(phone.getByRole('heading', { level: 1 })).toHaveText('Привет, Олег');
	expect(errors).toEqual([]);
	await ctx.close();
});

test('фон входа выбирает администратор, на телефоне страницы помещаются в экран', async ({
	page,
	browser
}) => {
	await login(page, ADMIN);
	await page.goto('/settings?tab=appearance');
	await page.getByRole('radio', { name: /Ночь/ }).click();
	await expect(page.getByRole('radio', { name: /Ночь/ })).toHaveAttribute('aria-checked', 'true');

	const ctx = await browser.newContext({ locale: 'ru-RU', viewport: { width: 375, height: 812 } });
	const phone = await ctx.newPage();
	await phone.goto('/login');
	await expect(phone.locator('main.auth')).toHaveClass(/login-bg-night/);
	await fitsScreen(phone);
	await login(phone, STUDENT);
	for (const path of ['/', '/homework', '/materials', '/subjects', '/members', '/profile']) {
		await phone.goto(path);
		await phone.waitForLoadState('networkidle');
		await fitsScreen(phone);
	}
	const viewport = await phone.locator('meta[name=viewport]').getAttribute('content');
	expect(viewport).toContain('maximum-scale=1');
	await ctx.close();

	// Вернуть фон по умолчанию, чтобы не влиять на снимки других тестов.
	await page.getByRole('radio', { name: /Сияние/ }).click();
	await expect(page.getByRole('radio', { name: /Сияние/ })).toHaveAttribute('aria-checked', 'true');
});
