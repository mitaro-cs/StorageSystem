import { expect, type Page } from '@playwright/test';

export const ADMIN = {
	username: 'anna.admin',
	password: 'e2e-admin-password',
	name: 'Смирнова Анна Сергеевна'
};
export const STUDENT = { username: 'oleg.kim', password: 'e2e-student-password', name: 'Ким Олег' };

export async function login(page: Page, user: { username: string; password: string }) {
	await page.goto('/login');
	await page.getByLabel('Имя пользователя').fill(user.username);
	await page.getByLabel('Пароль', { exact: true }).fill(user.password);
	await page.getByRole('button', { name: 'Войти' }).click();
	await expect(page.getByRole('heading', { level: 1 })).toContainText('Привет');
	await acceptTermsIfAsked(page);
}

/** Аккаунт создан без формы регистрации (администратором) — правила примем в окне согласия. */
export async function acceptTermsIfAsked(page: Page) {
	const accepted = await page.evaluate(() =>
		fetch('/api/me')
			.then((r) => r.json())
			.then((m) => m.user?.termsAccepted !== false)
	);
	if (accepted) return;
	const gate = page.getByRole('dialog', { name: 'Правила сайта' });
	await gate.getByRole('checkbox').check();
	await gate.getByRole('button', { name: 'Принимаю' }).click();
	await expect(gate).toBeHidden();
}

/** Собирает ошибки консоли: CSP-нарушения и исключения должны ломать тест. */
export function watchConsole(page: Page): string[] {
	const errors: string[] = [];
	page.on('console', (m) => {
		if (m.type() === 'error' && !m.text().includes('401')) errors.push(m.text());
	});
	page.on('pageerror', (e) => errors.push(e.message));
	return errors;
}
