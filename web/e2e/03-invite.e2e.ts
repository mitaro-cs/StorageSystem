import { expect, test } from '@playwright/test';
import { ADMIN, STUDENT, login, watchConsole } from './helpers';

test('студент регистрируется по инвайту, видит ДЗ и не видит управления', async ({
	page,
	browser
}) => {
	await login(page, ADMIN);
	await page.goto('/settings?tab=invites');
	await page.getByRole('button', { name: 'Создать ссылку' }).click();
	const link = await page.locator('.fresh .link').innerText();
	expect(link).toContain('/invite/');

	const ctx = await browser.newContext({ locale: 'ru-RU', viewport: { width: 390, height: 844 } });
	const student = await ctx.newPage();
	const errors = watchConsole(student);
	await student.goto(link);
	await expect(student.getByRole('heading', { name: 'БИН2509' })).toBeVisible();
	await student.getByLabel('ФИО').fill(STUDENT.name);
	await student.getByLabel('Имя пользователя для входа').fill(STUDENT.username);
	await student.getByLabel('Пароль', { exact: true }).fill(STUDENT.password);
	await student.getByLabel('Повторите пароль').fill(STUDENT.password);
	await student.getByRole('button', { name: 'Присоединиться' }).click();
	await expect(student.getByRole('heading', { level: 1 })).toHaveText('Привет, Олег');
	// Знакомство с сайтом — сразу после регистрации; закрыть можно в любой момент.
	const welcome = student.getByRole('dialog', { name: 'Знакомство с groupbase' });
	await expect(
		welcome.getByRole('heading', { name: 'Добро пожаловать в groupbase' })
	).toBeVisible();
	await welcome.getByRole('button', { name: 'Закрыть' }).click();
	await expect(welcome).toBeHidden();

	// Задание видно, отметка «выполнено» работает.
	await student.getByRole('link', { name: 'ДЗ' }).click();
	const toggle = student.getByRole('checkbox', { name: 'Типовой расчёт №1' });
	await toggle.click();
	await expect(toggle).toHaveAttribute('aria-checked', 'true');
	await student.screenshot({ path: 'test-results/shots/homework-mobile.png', fullPage: true });

	// Прав на публикацию и настройки у студента нет.
	await expect(student.getByRole('button', { name: 'Задание' })).toHaveCount(0);
	await student.goto('/settings');
	await expect(student.getByText('Здесь пока нечего настраивать')).toBeVisible();
	await student.goto('/news');
	await expect(student.getByRole('button', { name: 'Новость' })).toHaveCount(0);
	await student.screenshot({ path: 'test-results/shots/news-mobile.png', fullPage: true });
	expect(errors).toEqual([]);
	await ctx.close();
});
