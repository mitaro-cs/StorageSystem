import { expect, test, type Page } from '@playwright/test';
import { ADMIN, STUDENT, login, watchConsole } from './helpers';

/** Момент по местным часам: через days дней в hh:mm — строкой календаря в UTC. */
function at(days: number, h: number, m: number): string {
	const d = new Date();
	d.setDate(d.getDate() + days);
	d.setHours(h, m, 0, 0);
	return d
		.toISOString()
		.replace(/[-:]/g, '')
		.replace(/\.\d{3}/, '');
}

/** Выгрузка расписания, как с сайта вуза: лекции раз в неделю, практика, разовый классный час. */
function calendar(): string {
	return [
		'BEGIN:VCALENDAR',
		'VERSION:2.0',
		'PRODID:-//Расписание МТУСИ//RU',
		'BEGIN:VEVENT',
		'UID:ma-lec@e2e',
		`DTSTART:${at(1, 9, 30)}`,
		`DTEND:${at(1, 11, 5)}`,
		'RRULE:FREQ=WEEKLY;COUNT=6',
		'SUMMARY:Математический анализ (Лекция)',
		'LOCATION:А-214',
		'DESCRIPTION:Преподаватель: Петров А. В.',
		'END:VEVENT',
		'BEGIN:VEVENT',
		'UID:ma-pr@e2e',
		`DTSTART:${at(2, 11, 20)}`,
		`DTEND:${at(2, 12, 55)}`,
		'SUMMARY:Мат. анализ\\, пр.',
		'END:VEVENT',
		'BEGIN:VEVENT',
		'UID:curator@e2e',
		`DTSTART:${at(3, 15, 0)}`,
		`DTEND:${at(3, 16, 0)}`,
		'SUMMARY:Классный час',
		'END:VEVENT',
		'END:VCALENDAR'
	].join('\r\n');
}

/** Ctrl K при русской раскладке: браузер присылает «л», а не «k». */
async function ctrlKRussian(page: Page) {
	await page.evaluate(() =>
		window.dispatchEvent(
			new KeyboardEvent('keydown', { key: 'л', code: 'KeyK', ctrlKey: true, bubbles: true })
		)
	);
}

test('расписание из файла календаря: загрузка, «Сегодня», пара и задание к ней', async ({
	page
}) => {
	const errors = watchConsole(page);
	await login(page, ADMIN);
	await page.goto('/schedule');
	await page.getByRole('button', { name: 'Из файла календаря' }).click();
	const dialog = page.getByRole('dialog', { name: 'Расписание из файла календаря' });
	await dialog.locator('input[type=file]').setInputFiles({
		name: 'raspisanie.ics',
		mimeType: 'text/calendar',
		buffer: Buffer.from(calendar())
	});
	// Предпросмотр: 8 пар, «Мат. анализ» — тот же предмет, разовый классный час — без предмета.
	await expect(dialog.getByRole('button', { name: 'Загрузить 8 пар' })).toBeVisible();
	await expect(dialog.getByLabel('Предмет для «Математический анализ»')).toHaveValue(/^subject:/);
	await expect(dialog.getByLabel('Предмет для «Мат. анализ»')).toHaveValue(/^subject:/);
	await expect(dialog.getByLabel('Предмет для «Классный час»')).toHaveValue('none');
	await dialog.getByRole('button', { name: 'Загрузить 8 пар' }).click();
	await expect(dialog).toBeHidden();

	// Завтрашние пары — на «Сегодня».
	await page.goto('/');
	const block = page.getByRole('region', { name: /Завтра|Пары сегодня/ });
	await expect(block.getByRole('link', { name: 'Математический анализ' })).toBeVisible();
	await block.getByRole('link', { name: 'Математический анализ' }).click();
	await expect(
		page.getByRole('heading', { level: 1, name: 'Математический анализ' })
	).toBeVisible();
	await expect(page.getByText('А-214')).toBeVisible();
	await expect(page.getByText('Петров А. В.')).toBeVisible();

	// Задание к паре — сразу на её странице.
	await page
		.getByRole('toolbar', { name: 'Добавить к паре' })
		.getByRole('button', {
			name: 'Задание к паре'
		})
		.click();
	const hw = page.getByRole('dialog', { name: 'Новое задание' });
	await expect(hw.getByRole('group', { name: 'Срок — к паре' })).toBeVisible();
	await hw.getByLabel('Что сделать').fill('Задачи к лекции');
	await hw.getByRole('button', { name: 'Опубликовать' }).click();
	await expect(hw).toBeHidden();
	await expect(page.getByRole('link', { name: 'Задачи к лекции' })).toBeVisible();

	// У предмета — вкладка «Пары».
	await page.getByRole('link', { name: 'Предмет: Математический анализ' }).click();
	await page
		.getByRole('navigation', { name: 'Разделы предмета' })
		.getByRole('link', { name: 'Пары' })
		.click();
	await expect(page.getByRole('heading', { name: 'Ближайшие пары' })).toBeVisible();
	expect(errors).toEqual([]);
});

test('студент видит расписание и сам добавляет задания; Ctrl K работает и по-русски', async ({
	page
}) => {
	const errors = watchConsole(page);
	await login(page, STUDENT);
	await page.goto('/schedule');
	await expect(page.getByRole('heading', { name: 'Расписание' })).toBeVisible();
	// Вести расписание студент не может — кнопок загрузки нет.
	await expect(page.getByRole('button', { name: 'Из файла календаря' })).toHaveCount(0);

	// Задания студенты добавляют сами (просьба владельца).
	await page.goto('/homework');
	await expect(page.getByRole('button', { name: 'Задание' })).toBeVisible();

	await ctrlKRussian(page);
	await expect(page.getByRole('combobox')).toBeVisible();
	await page.keyboard.press('Escape');
	expect(errors).toEqual([]);
});

test('своя картинка на фоне — в своих цветах, без оттенка темы; «Аура» по референсу', async ({
	page
}) => {
	const errors = watchConsole(page);
	await login(page, STUDENT);
	await page.goto('/profile?tab=appearance');
	const html = page.locator('html');
	await page
		.getByRole('radiogroup', { name: 'Цвет' })
		.getByRole('radio', { name: 'Зелёный' })
		.click();
	// Своя картинка — зелёная тема не должна её перекрашивать.
	const png = Buffer.from(
		'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
		'base64'
	);
	await page.locator('input[type=file][accept="image/*"]').setInputFiles({
		name: 'photo.png',
		mimeType: 'image/png',
		buffer: png
	});
	await expect(html).toHaveAttribute('data-bg', 'custom');
	const veil = () =>
		page.evaluate(() => getComputedStyle(document.documentElement, '::before').backgroundImage);
	// Вуаль — белая (светлая тема), не цвета темы.
	expect(await veil()).toContain('rgba(255, 255, 255, 0.25)');
	await page.getByLabel('Приглушить картинку').evaluate((el: HTMLInputElement) => {
		el.value = '0';
		el.dispatchEvent(new Event('input', { bubbles: true }));
	});
	expect(await veil()).toContain('rgba(255, 255, 255, 0)');
	await page.getByRole('switch', { name: 'Размыть картинку' }).click();
	await expect(html).toHaveAttribute('data-bg-blur', '');

	// «Аура»: яркие пятна и круг с перекрестием — поверх своей картинки их нет.
	await page.getByRole('button', { name: 'Убрать' }).click();
	await page
		.getByRole('radiogroup', { name: 'Дизайн' })
		.getByRole('radio', { name: 'Аура' })
		.click();
	await expect(html).toHaveAttribute('data-style', 'aura');
	const after = await page.evaluate(() => getComputedStyle(document.body, '::after').maskImage);
	expect(after).toContain('svg');

	// Вернуть как было — для остальных тестов.
	await page
		.getByRole('radiogroup', { name: 'Дизайн' })
		.getByRole('radio', { name: 'Классика' })
		.click();
	await page
		.getByRole('radiogroup', { name: 'Цвет' })
		.getByRole('radio', { name: 'Чернила' })
		.click();
	await expect(html).not.toHaveAttribute('data-style', /.+/);
	expect(errors).toEqual([]);
});
