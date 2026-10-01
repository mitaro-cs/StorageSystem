import { expect, test } from '@playwright/test';
import { STUDENT, login, watchConsole } from './helpers';

test('подсказка раздела — один раз, тур — снова из профиля', async ({ page }) => {
	const errors = watchConsole(page);
	await login(page, STUDENT);
	// Студент зарегистрировался в 03 — для него подсказки разделов ещё не закрыты.
	await page.goto('/schedule');
	const tip = page.getByRole('note').filter({ hasText: 'Нажмите на день в полосе' });
	await expect(tip).toBeVisible();
	await tip.getByRole('button', { name: 'Понятно, скрыть подсказку' }).click();
	await expect(tip).toBeHidden();
	// Закрыта на сервере: после перезагрузки (и на других устройствах) её нет.
	await page.reload();
	await expect(page.getByRole('heading', { level: 1 })).toHaveText('Расписание');
	await page.waitForTimeout(800);
	await expect(page.getByRole('note').filter({ hasText: 'Нажмите на день в полосе' })).toHaveCount(
		0
	);

	// «Как пользоваться» в профиле — тот же тур; Esc закрывает.
	await page.goto('/profile?tab=app');
	await page.getByRole('button', { name: 'Пройти тур' }).click();
	const tour = page.getByRole('dialog', { name: 'Знакомство с groupbase' });
	await tour.getByRole('button', { name: 'Поехали' }).click();
	await expect(page).toHaveURL(/\/$/);
	await expect(tour.getByRole('heading', { name: 'Всё главное — на «Сегодня»' })).toBeVisible();
	await page.keyboard.press('ArrowRight');
	await expect(tour.getByText('2 из')).toBeVisible();
	await page.keyboard.press('Escape');
	await expect(tour).toBeHidden();
	expect(errors).toEqual([]);
});
