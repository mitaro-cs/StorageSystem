import { expect, test, type Page } from '@playwright/test';
import { ADMIN, login, watchConsole } from './helpers';

const day = (offset: number) => {
	const d = new Date(Date.now() + offset * 86_400_000);
	const p = (n: number) => String(n).padStart(2, '0');
	return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
};

async function fitsScreen(page: Page) {
	const [scroll, client] = await page.evaluate(() => [
		document.documentElement.scrollWidth,
		document.documentElement.clientWidth
	]);
	expect(scroll).toBeLessThanOrEqual(client);
}

test('сессия: экзамен с аудиторией, даты, отсчёт на главной и отметка «сдано»', async ({
	page
}) => {
	const errors = watchConsole(page);
	await login(page, ADMIN);

	await page.goto('/homework?new=1');
	const dialog = page.getByRole('dialog');
	await dialog.getByRole('radio', { name: 'Экзамен' }).click();
	await expect(dialog.getByLabel('Когда')).toBeVisible();
	await dialog.getByLabel('Предмет').selectOption({ label: 'Математический анализ' });
	await dialog.getByLabel('Когда').fill(`${day(7)}T09:00`);
	await dialog.getByLabel(/Где/).fill('ауд. 305');
	await dialog.getByLabel('Название').fill('Экзамен по матанализу');
	await dialog.getByRole('button', { name: 'Опубликовать' }).click();
	await expect(dialog).toBeHidden({ timeout: 20_000 });

	await page.goto('/session');
	await expect(page.getByRole('heading', { name: 'Сессия', level: 1 })).toBeVisible();
	const row = page.getByRole('listitem').filter({ hasText: 'Экзамен по матанализу' });
	await expect(row).toContainText('ауд. 305');
	await expect(row).toContainText('Экзамен');

	// Даты сессии — у старосты.
	await page.getByRole('button', { name: 'Даты' }).click();
	const dates = page.getByRole('dialog', { name: 'Даты сессии' });
	await dates.getByLabel('Первый день').fill(day(5));
	await dates.getByLabel('Последний день').fill(day(20));
	await dates.getByRole('button', { name: 'Сохранить' }).click();
	await expect(dates).toBeHidden();
	await expect(page.getByText(/начнётся через \d+ д/)).toBeVisible();

	// Отметка «сдано» — личная, прогресс считается сразу.
	await row.getByRole('checkbox').click();
	await expect(page.getByText('1/1')).toBeVisible();
	await row.getByRole('checkbox').click();
	await expect(page.getByText('0/1')).toBeVisible();

	// На главной — карточка сессии с обратным отсчётом.
	await page.goto('/');
	const card = page
		.getByRole('link', { name: /Сессия/ })
		.filter({ hasText: 'Математический анализ' });
	await expect(card).toBeVisible();
	await expect(card).toContainText('до экзамена');

	await page.setViewportSize({ width: 375, height: 800 });
	await page.goto('/session');
	await expect(row).toBeVisible();
	await fitsScreen(page);
	await page.goto('/homework');
	await expect(page.getByRole('link', { name: 'Экзамен по матанализу' })).toBeVisible();
	await fitsScreen(page);
	expect(errors).toEqual([]);
});

test('мастер «Новый семестр»: новые предметы списком, повтор пропускается', async ({ page }) => {
	const errors = watchConsole(page);
	await login(page, ADMIN);
	await page.goto('/subjects');
	await page.getByRole('button', { name: 'Новый семестр' }).click();
	const wizard = page.getByRole('dialog', { name: 'Новый семестр' });
	await expect(wizard.getByText('Математический анализ')).toBeVisible();
	await wizard.getByRole('button', { name: /Дальше/ }).click();

	await wizard
		.getByLabel('Новые предметы')
		.fill('Теория вероятностей — Ким О. С.\nМатематический анализ\nЭлектроника');
	await expect(wizard.getByText('уже есть — пропустим')).toBeVisible();
	await expect(wizard.getByText('Ким О. С.')).toBeVisible();
	await wizard.getByRole('button', { name: /Дальше/ }).click();
	await wizard.getByRole('button', { name: /Дальше/ }).click();
	await expect(wizard).toContainText('Новых: 2');
	await wizard.getByRole('button', { name: 'Готово' }).click();
	await expect(wizard).toBeHidden({ timeout: 20_000 });

	await expect(page.getByRole('link', { name: /Теория вероятностей/ }).first()).toBeVisible();
	await expect(page.getByRole('link', { name: /Электроника/ }).first()).toBeVisible();
	// Повтор не создался.
	await expect(page.locator('main a.subject', { hasText: 'Математический анализ' })).toHaveCount(1);
	expect(errors).toEqual([]);
});
