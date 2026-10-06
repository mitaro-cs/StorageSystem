import { expect, test } from '@playwright/test';
import { ADMIN, STUDENT, login, watchConsole } from './helpers';

test('подсказка раздела – один раз, тур – снова из профиля', async ({ page }) => {
	const errors = watchConsole(page);
	await login(page, STUDENT);
	// Студент зарегистрировался в 03 – для него подсказки разделов ещё не закрыты.
	await page.goto('/schedule');
	const tip = page.getByRole('note').filter({ hasText: 'Выберите день' });
	await expect(tip).toBeVisible();
	await tip.getByRole('button', { name: 'Понятно, скрыть подсказку' }).click();
	await expect(tip).toBeHidden();
	// Закрыта на сервере: после перезагрузки (и на других устройствах) её нет.
	await page.reload();
	await expect(page.getByRole('heading', { level: 1 })).toHaveText('Расписание');
	await page.waitForTimeout(800);
	await expect(page.getByRole('note').filter({ hasText: 'Выберите день' })).toHaveCount(0);

	// «Как пользоваться» в профиле – тот же тур; Esc закрывает.
	await page.goto('/profile?tab=app');
	await page.getByRole('button', { name: 'Пройти тур' }).click();
	const tour = page.getByRole('dialog', { name: 'Знакомство с Campus' });
	await tour.getByRole('button', { name: 'Поехали' }).click();
	await expect(page).toHaveURL(/\/$/);
	await expect(tour.getByRole('heading', { name: 'Всё главное – на «Сегодня»' })).toBeVisible();
	await page.keyboard.press('ArrowRight');
	await expect(tour.getByText('2 из')).toBeVisible();
	await page.keyboard.press('Escape');
	await expect(tour).toBeHidden();
	expect(errors).toEqual([]);
});

test('материалы: сообщение из чата и закрепление сверху', async ({ page }) => {
	const errors = watchConsole(page);
	await login(page, ADMIN);
	await page.goto('/subjects');
	await page.locator('main a[href^="/subjects/"]').first().click();
	// Первая вкладка предмета – задания, «Новости» – сразу за ними.
	const tabs = page.getByRole('navigation', { name: 'Разделы предмета' }).getByRole('link');
	await expect(tabs.nth(0)).toHaveText('ДЗ');
	await expect(tabs.nth(1)).toHaveText('Новости');
	await page
		.getByRole('navigation', { name: 'Разделы предмета' })
		.getByRole('link', { name: 'Материалы' })
		.click();
	await page.getByRole('button', { name: 'Добавить' }).click();
	const dialog = page.getByRole('dialog', { name: 'Добавить материал' });
	await dialog.getByRole('tab', { name: 'Сообщение' }).click();
	await dialog.getByLabel('Текст сообщения').fill('Билеты к экзамену\n1. Пределы\n2. Ряды');
	await dialog.getByRole('button', { name: 'Добавить' }).click();
	await expect(dialog).toBeHidden();
	const row = page.locator('.mrow', { hasText: 'Билеты к экзамену' });
	await expect(row).toBeVisible();
	await expect(row.getByText('1. Пределы')).toBeVisible();
	await row.getByRole('button', { name: /Ещё|Действия/ }).click();
	await page.getByRole('menuitem', { name: 'Закрепить сверху' }).click();
	await expect(page.locator('.mrow').first()).toContainText('Билеты к экзамену');
	await expect(page.locator('.mrow.pinned')).toHaveCount(1);
	expect(errors).toEqual([]);
});
