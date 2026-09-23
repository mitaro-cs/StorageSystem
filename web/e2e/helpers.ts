import { expect, type Page } from '@playwright/test';

export const ADMIN = {
	username: 'anna.admin',
	password: 'e2e-admin-password',
	name: 'Анна Староста'
};
export const STUDENT = { username: 'oleg.kim', password: 'e2e-student-password', name: 'Олег Ким' };

export async function login(page: Page, user: { username: string; password: string }) {
	await page.goto('/login');
	await page.getByLabel('Имя пользователя').fill(user.username);
	await page.getByLabel('Пароль', { exact: true }).fill(user.password);
	await page.getByRole('button', { name: 'Войти' }).click();
	await expect(page.getByRole('heading', { level: 1 })).toContainText('Привет');
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
