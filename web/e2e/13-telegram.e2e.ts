import { expect, test } from '@playwright/test';
import { ADMIN, STUDENT, login, watchConsole } from './helpers';

test('чаты Telegram: закрепить на главной и у предмета, перейти из приложения', async ({
	page,
	browser
}) => {
	const errors = watchConsole(page);
	await login(page, ADMIN);

	// Староста закрепляет чат прямо с главной: кнопка — в строке быстрых действий.
	await page.getByRole('button', { name: 'Закрепить чат' }).click();
	const dialog = page.getByRole('dialog');
	await dialog.getByLabel('Ссылка на чат или канал').fill('@bin2509_chat');
	await dialog.getByRole('button', { name: 'Закрепить' }).click();
	await expect(dialog.getByText('t.me/bin2509_chat')).toBeVisible();
	// Ссылка не на Telegram не принимается.
	await dialog.getByLabel('Ссылка на чат или канал').fill('https://vk.com/club1');
	await dialog.getByRole('button', { name: 'Закрепить' }).click();
	await expect(dialog.getByRole('alert')).toContainText('Telegram');
	await dialog.getByRole('button', { name: 'Готово' }).click();
	const chip = page.getByRole('link', { name: 'Чат группы' });
	await expect(chip).toHaveAttribute('href', 'https://t.me/bin2509_chat');
	await expect(chip).toHaveAttribute('target', '_blank');

	// Чат предмета — в карточке предмета.
	await page.goto('/subjects');
	await page
		.getByRole('link', { name: /Математический анализ/ })
		.first()
		.click();
	await page.getByRole('button', { name: 'Действия' }).click();
	await page.getByRole('menuitem', { name: 'Изменить' }).click();
	await page.getByLabel('Чат предмета в Telegram').fill('t.me/matan_bin2509');
	await page.getByRole('button', { name: 'Сохранить' }).click();
	await expect(page.getByRole('link', { name: 'Чат предмета' })).toHaveAttribute(
		'href',
		'https://t.me/matan_bin2509'
	);
	await page.screenshot({ path: 'test-results/shots/subject-chat.png' });
	// 400 — наш же отказ принять ссылку на vk.com выше.
	expect(errors.filter((e) => !e.includes('status of 400'))).toEqual([]);

	// Студент видит оба чата и может в них перейти.
	const ctx = await browser.newContext({ locale: 'ru-RU', viewport: { width: 390, height: 844 } });
	const student = await ctx.newPage();
	await login(student, STUDENT);
	await expect(student.getByRole('link', { name: 'Чат группы' })).toBeVisible();
	await expect(student.getByRole('button', { name: 'Закрепить чат' })).toHaveCount(0);
	await student.screenshot({ path: 'test-results/shots/today-chats-mobile.png' });
	await student.goto('/subjects');
	await expect(
		student.getByRole('link', { name: 'Чат предмета «Математический анализ» в Telegram' })
	).toHaveAttribute('href', 'https://t.me/matan_bin2509');
	await ctx.close();
});
