import { expect, test } from '@playwright/test';
import { ADMIN, login, watchConsole } from './helpers';

test('тесты онлайн: создать в предмете, пройти, увидеть разбор и результаты', async ({ page }) => {
	const errors = watchConsole(page);
	await login(page, ADMIN);
	await page.goto('/subjects');
	await page.locator('main a[href^="/subjects/"]').first().click();
	await page
		.getByRole('navigation', { name: 'Разделы предмета' })
		.getByRole('link', { name: 'Тесты' })
		.click();
	await page.getByRole('button', { name: 'Создать тест' }).click();

	const dialog = page.getByRole('dialog', { name: 'Новый тест' });
	await dialog.getByLabel('Название', { exact: true }).fill('Пределы – разминка');
	await dialog.getByLabel('Текст вопроса 1').fill('Сколько будет 2 + 2?');
	await dialog.getByLabel('Вариант 1', { exact: true }).fill('3');
	await dialog.getByLabel('Вариант 2', { exact: true }).fill('4');
	await dialog.getByRole('button', { name: 'Верный вариант 2' }).click();
	await dialog.getByRole('button', { name: 'Добавить вопрос' }).click();
	await dialog.getByRole('radio', { name: 'Ответ словом' }).last().click();
	await dialog.getByLabel('Текст вопроса 2').fill('Столица России');
	await dialog.getByLabel('Верные ответы – каждый с новой строки').fill('Москва');
	await dialog.getByRole('button', { name: 'Опубликовать' }).click();
	await expect(dialog).toBeHidden();

	await page.getByRole('link', { name: 'Пределы – разминка' }).click();
	await page.getByRole('button', { name: 'Начать' }).click();
	await page.getByRole('radio', { name: '4' }).click();
	await page.getByLabel('Ответ на вопрос 2').fill('  москва ');
	await page.getByRole('button', { name: 'Завершить и проверить' }).click();
	await expect(page.getByText('2 из 2 (100 %)')).toBeVisible();

	// Ведущий видит себя в результатах.
	await page.getByRole('button', { name: 'К тесту' }).click();
	await expect(page.getByRole('region', { name: 'Результаты' })).toContainText('2 из 2 (100 %)');
	expect(errors).toEqual([]);
});
