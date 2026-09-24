import { expect, test } from '@playwright/test';
import { ADMIN, STUDENT, login, watchConsole } from './helpers';

test('новости на главной заметнее, комментарии подписаны именами', async ({ browser }) => {
	const ctx = await browser.newContext({
		locale: 'ru-RU',
		timezoneId: 'Europe/Moscow',
		viewport: { width: 390, height: 844 }
	});
	const page = await ctx.newPage();
	const errors = watchConsole(page);
	await login(page, ADMIN);

	// На телефоне нет отдельного меню: разделы — в нижней панели, на главной и в профиле.
	await expect(page.getByRole('button', { name: 'Меню и предметы' })).toHaveCount(0);
	const news = page.getByRole('heading', { name: 'Новости', level: 2 });
	const deadlines = page.getByRole('heading', { name: 'Дедлайны', level: 2 });
	await expect(news).toBeVisible();
	const [n, d] = await Promise.all([news.boundingBox(), deadlines.boundingBox()]);
	expect(n!.y).toBeLessThan(d!.y);
	// Срочная новость — с янтарной плашкой по верху карточки.
	await expect(page.locator('.news.urgent .band').first()).toHaveText(/Срочно/i);
	await page.screenshot({ path: 'test-results/shots/today-news-mobile.png' });

	// Комментарии: только имя, без ФИО.
	await page.getByRole('link', { name: 'Перенос пары в четверг' }).first().click();
	await page.getByPlaceholder('Написать комментарий…').fill('Спасибо, учту');
	await page.getByRole('button', { name: 'Отправить' }).click();
	const comment = page.locator('.comment').filter({ hasText: 'Спасибо, учту' });
	await expect(comment.locator('.name')).toHaveText('Анна');
	await expect(comment.locator('.name')).toHaveAttribute('title', ADMIN.name);
	expect(errors).toEqual([]);
	await ctx.close();
});

test('режим управления прячет кнопки администратора', async ({ page }) => {
	const errors = watchConsole(page);
	await login(page, ADMIN);
	await page.goto('/settings?tab=server');
	await expect(page.getByRole('heading', { name: 'Доступ для группы' })).toBeVisible();
	await expect(page.getByRole('radio', { name: /CloudPub/ })).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Резервные копии' })).toBeVisible();
	await page.screenshot({ path: 'test-results/shots/settings-server.png', fullPage: true });

	await page.goto('/profile');
	const toggle = page.getByRole('switch', { name: 'Режим управления' }).first();
	await expect(toggle).toHaveAttribute('aria-checked', 'true');
	await toggle.click();
	await expect(toggle).toHaveAttribute('aria-checked', 'false');
	await page.goto('/settings');
	await expect(page.getByText('Режим управления выключен')).toBeVisible();
	await page.goto('/members');
	await expect(page.getByRole('link', { name: 'Пригласить' })).toHaveCount(0);

	// Настройка хранится на сервере: после перезагрузки остаётся выключенной.
	await page.reload();
	await page.goto('/settings');
	await page.getByRole('button', { name: 'Включить режим управления' }).click();
	await expect(page.getByRole('button', { name: 'Приглашения' })).toBeVisible();
	expect(errors).toEqual([]);
});

test('компьютер хоста выключен: вместо ответа сервера — страница туннеля', async ({ browser }) => {
	test.setTimeout(60_000);
	// Без service worker: проверяем, что приложение само распознаёт чужой ответ.
	const ctx = await browser.newContext({
		locale: 'ru-RU',
		timezoneId: 'Europe/Moscow',
		serviceWorkers: 'block'
	});
	const page = await ctx.newPage();
	await login(page, STUDENT);
	await page.goto('/profile#offline');
	await expect(page.locator('#offline').getByText('ещё ни разу')).toHaveCount(0, {
		timeout: 15_000
	});
	await page.goto('/');
	await expect(page.getByRole('heading', { level: 1 })).toContainText('Привет');

	// Туннель отвечает своей страницей ошибки — без метки сервера группы.
	await page.route('**/api/**', (route) =>
		route.fulfill({ status: 404, contentType: 'text/html', body: '<h1>Tunnel not found</h1>' })
	);
	await page.getByRole('link', { name: 'Домашние задания' }).first().click();
	await expect(
		page.getByText(/Сервер группы выключен — показаны сохранённые данные/)
	).toBeVisible();
	await expect(page.getByText('Типовой расчёт №1').first()).toBeVisible();
	await page.screenshot({ path: 'test-results/shots/host-offline.png' });

	// Сервер вернулся — баннер пропадает при следующем запросе.
	await page.unroute('**/api/**');
	await page.getByRole('link', { name: 'Сегодня' }).first().click();
	await expect(page.getByText(/Сервер группы выключен/)).toHaveCount(0, { timeout: 10_000 });
	await ctx.close();
});
