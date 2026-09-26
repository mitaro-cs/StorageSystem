import { expect, test, type Page } from '@playwright/test';
import { ADMIN, STUDENT, login, watchConsole } from './helpers';

/** Запрос к API от имени вошедшего: с CSRF-ключом из cookie, как у страниц. */
async function api(page: Page, method: string, path: string, body?: unknown) {
	return page.evaluate(
		async ([m, p, b]) => {
			const csrf =
				document.cookie
					.split('; ')
					.find((c) => /^(__Host-)?gb_csrf=/.test(c))
					?.split('=')[1] ?? '';
			const r = await fetch(p as string, {
				method: m as string,
				headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf },
				body: b === undefined ? undefined : JSON.stringify(b)
			});
			return { status: r.status, body: await r.json().catch(() => null) };
		},
		[method, path, body] as const
	);
}

const PNG = Buffer.from(
	'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
	'base64'
);

test('подгруппы: выбрал свою — другая пропадает из списков, вернуть можно', async ({
	page,
	browser
}) => {
	const errors = watchConsole(page);
	await login(page, ADMIN);
	const group = (await api(page, 'GET', '/api/me')).body.groups[0].id;
	for (const name of ['Английский язык №1 Сильная группа', 'Английский язык №2 Слабая группа'])
		expect((await api(page, 'POST', `/api/groups/${group}/subjects`, { name })).status).toBe(200);

	const ctx = await browser.newContext({ locale: 'ru-RU' });
	const student = await ctx.newPage();
	await login(student, STUDENT);
	const card = student.getByRole('region', { name: /Подгруппа: Английский язык/ });
	await expect(card).toBeVisible();
	await card.getByRole('button', { name: '№1 Сильная группа' }).click();
	await expect(card).toHaveCount(0);

	const side = student.getByRole('complementary', { name: 'Навигация' });
	await expect(side.getByRole('link', { name: /Английский язык №1/ })).toBeVisible();
	await expect(side.getByRole('link', { name: /Английский язык №2/ })).toHaveCount(0);

	await student.goto('/subjects');
	await expect(student.getByRole('heading', { name: 'Не мои предметы' })).toBeVisible();
	await student.getByRole('link', { name: /Английский язык №2/ }).click();
	await expect(student.getByText('Не ваш предмет.')).toBeVisible();
	await student.getByRole('button', { name: 'Это мой предмет' }).click();
	await expect(student.getByText('Не ваш предмет.')).toHaveCount(0);
	await expect(side.getByRole('link', { name: /Английский язык №2/ })).toBeVisible();
	await ctx.close();
	expect(errors).toEqual([]);
});

test('новость с фото; случайный клик мимо окна не стирает написанное', async ({ page }) => {
	const errors = watchConsole(page);
	await login(page, ADMIN);
	await page.goto('/news?new=1');
	const dialog = page.getByRole('dialog', { name: 'Новая новость' });
	await dialog.getByLabel('Заголовок').fill('Фото расписания');
	await dialog.locator('input[type=file]:not([capture])').setInputFiles({
		name: 'расписание.png',
		mimeType: 'image/png',
		buffer: PNG
	});
	// Загрузилось — в списке вложений одна строка (строка загрузки уезжает с анимацией).
	await expect(dialog.getByText('расписание.png')).toHaveCount(1);

	// Клик по затемнению вокруг окна — вопрос, а не потеря текста.
	await page.mouse.click(5, 5);
	const confirm = page.getByRole('dialog', { name: 'Закрыть без сохранения?' });
	await expect(confirm).toBeVisible();
	await confirm.getByRole('button', { name: 'Продолжить' }).click();
	await expect(dialog.getByLabel('Заголовок')).toHaveValue('Фото расписания');

	await dialog.getByRole('button', { name: 'Опубликовать' }).click();
	await expect(dialog).toBeHidden();
	const card = page.locator('article', { hasText: 'Фото расписания' }).first();
	await expect(card.locator('img[src^="/api/files/"]')).toBeVisible();
	await card.locator('img[src^="/api/files/"]').click();
	await expect(page.getByRole('dialog', { name: 'Просмотр: расписание.png' })).toBeVisible();
	expect(errors).toEqual([]);
});

test('палитра: «?» показывает горячие клавиши; модератор удаляет новость прямо с главной', async ({
	page
}) => {
	await login(page, ADMIN);
	await page.keyboard.press('Control+k');
	await page.getByRole('combobox').fill('?');
	await expect(page.getByRole('heading', { name: 'Горячие клавиши' })).toBeVisible();
	await page.keyboard.press('Escape');

	const group = (await api(page, 'GET', '/api/me')).body.groups[0].id;
	const made = await api(page, 'POST', '/api/news', {
		title: 'Плохая новость',
		body: 'Спам',
		groupIds: [group]
	});
	expect(made.status).toBe(200);
	await page.goto('/');
	const card = page.locator('article', { hasText: 'Плохая новость' });
	await card.getByRole('button', { name: 'Действия' }).click();
	await page.getByRole('menuitem', { name: 'Удалить' }).click();
	await page
		.getByRole('dialog', { name: 'Подтвердите' })
		.getByRole('button', { name: 'Удалить' })
		.click();
	await expect(card).toHaveCount(0);
});
