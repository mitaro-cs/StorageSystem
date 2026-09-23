import { expect, test } from '@playwright/test';
import { ADMIN, STUDENT, login, watchConsole } from './helpers';

test('студент видит уведомление о новости и настраивает push', async ({ page, browser }) => {
	await login(page, ADMIN);
	await page.goto('/news');
	await page.getByRole('button', { name: 'Новость' }).click();
	await page.getByLabel('Заголовок').fill('Собрание группы в пятницу');
	await page.getByRole('button', { name: 'Опубликовать' }).click();
	await expect(page.getByRole('link', { name: 'Собрание группы в пятницу' })).toBeVisible();

	const ctx = await browser.newContext({ locale: 'ru-RU', viewport: { width: 390, height: 844 } });
	const student = await ctx.newPage();
	const errors = watchConsole(student);
	await login(student, STUDENT);
	const bellLink = student.getByRole('link', { name: /Уведомления: непрочитанных \d+/ });
	await expect(bellLink).toBeVisible();
	await student.screenshot({ path: 'test-results/shots/bell-mobile.png' });
	await bellLink.click();
	await expect(student.getByRole('heading', { level: 1 })).toHaveText('Уведомления');
	await student.screenshot({ path: 'test-results/shots/notifications-mobile.png', fullPage: true });
	await student.getByRole('link', { name: /Собрание группы в пятницу/ }).click();
	await expect(student).toHaveURL(/\/news\/\d+$/);
	await expect(student.getByRole('link', { name: /Уведомления: непрочитанных/ })).toHaveCount(0);

	await student.goto('/profile#notifications');
	const reminders = student.getByRole('switch', { name: 'Напоминание за сутки до срока' });
	await expect(reminders).toHaveAttribute('aria-checked', 'true');
	await reminders.click();
	await expect(reminders).toHaveAttribute('aria-checked', 'false');
	await student.reload();
	await expect(
		student.getByRole('switch', { name: 'Напоминание за сутки до срока' })
	).toHaveAttribute('aria-checked', 'false');
	await student.screenshot({
		path: 'test-results/shots/notify-settings-mobile.png',
		fullPage: true
	});
	expect(errors).toEqual([]);
	await ctx.close();
});
