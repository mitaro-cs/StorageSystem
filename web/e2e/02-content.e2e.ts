import { expect, test } from '@playwright/test';
import { ADMIN, login, watchConsole } from './helpers';

test('староста создаёт предмет, задание и новость', async ({ page }) => {
	const errors = watchConsole(page);
	await login(page, ADMIN);

	await page.goto('/subjects');
	await page.getByRole('button', { name: 'Предмет' }).click();
	await page.getByLabel('Название').fill('Математический анализ');
	await page.getByLabel('Преподаватель').fill('Петрова Е. А.');
	await page.getByRole('button', { name: 'Создать' }).click();
	await expect(page.getByRole('link', { name: /Математический анализ/ }).first()).toBeVisible();

	await page.goto('/homework');
	await page.getByRole('button', { name: 'Задание' }).click();
	await page.getByLabel('Что сделать').fill('Типовой расчёт №1');
	await page.getByRole('textbox', { name: 'Подробности' }).fill('Задачи **1–12** из сборника');
	await page.getByRole('button', { name: 'Опубликовать' }).click();
	await expect(page.getByRole('link', { name: 'Типовой расчёт №1' })).toBeVisible();

	await page.goto('/news');
	await page.getByRole('button', { name: 'Новость' }).click();
	await page.getByLabel('Заголовок').fill('Перенос пары в четверг');
	await page.getByRole('textbox', { name: 'Текст' }).fill('Лекция переносится в **ауд. 314**.');
	await page.getByLabel('Срочно').check();
	await page.getByRole('button', { name: 'Опубликовать' }).click();
	await expect(page.getByRole('link', { name: 'Перенос пары в четверг' })).toBeVisible();

	await page.goto('/');
	await expect(page.getByText('Типовой расчёт №1')).toBeVisible();
	await page.screenshot({ path: 'test-results/shots/today-desktop.png', fullPage: true });
	expect(errors).toEqual([]);
});
