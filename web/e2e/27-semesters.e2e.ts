import { expect, test, type Page } from '@playwright/test';
import { ADMIN, login, watchConsole } from './helpers';

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

test('архив семестра: собрать, увидеть по семестрам, вернуть предметы', async ({ page }) => {
	const errors = watchConsole(page);
	await login(page, ADMIN);
	const me = await api(page, 'GET', '/api/me');
	const group = me.body.groups[0].id as number;
	const name = `Теория цепей ${Date.now() % 100000}`;
	const created = await api(page, 'POST', `/api/groups/${group}/subjects`, { name });
	expect(created.status).toBe(200);

	await page.goto('/settings?tab=semester');
	await page.getByRole('button', { name: 'Создать', exact: true }).click();
	const dialog = page.getByRole('dialog', { name: 'Архив семестра' });
	await dialog.getByLabel('Название').fill('Осень e2e');
	await dialog.getByRole('checkbox', { name }).check();
	await dialog.getByRole('button', { name: /В архив: 1 предмет/ }).click();
	await expect(dialog).toBeHidden();
	await expect(page.getByRole('link', { name: 'Осень e2e' })).toBeVisible();

	await page.getByRole('link', { name: 'Осень e2e' }).click();
	const section = page.getByRole('region', { name: 'Осень e2e' });
	await expect(section).toContainText(name);

	await section.getByRole('button', { name: 'Действия с архивом' }).click();
	await page.getByRole('menuitem', { name: 'Вернуть предметы' }).click();
	await page.getByRole('dialog').getByRole('button', { name: 'Вернуть' }).click();
	await expect(section).toBeHidden();
	await expect(page.locator('main .grid').first()).toContainText(name);
	expect(errors).toEqual([]);
});

test('прошлый семестр вручную: пустой архив, предмет в него и конспект', async ({ page }) => {
	const errors = watchConsole(page);
	await login(page, ADMIN);
	await page.goto('/subjects?archive=1');
	await page.getByRole('button', { name: 'Прошлый семестр' }).click();
	const ask = page.getByRole('dialog', { name: 'Новый архив' });
	await ask.getByRole('textbox').fill('Весна e2e');
	await ask.getByRole('button', { name: 'Создать' }).click();
	const section = page.getByRole('region', { name: 'Весна e2e' });
	await expect(section).toContainText('Пока пусто');

	const subject = `Электроника ${Date.now() % 100000}`;
	await section.getByRole('button', { name: 'Добавить предмет' }).click();
	const add = page.getByRole('dialog', { name: 'Предмет в архив' });
	await add.getByRole('textbox').fill(subject);
	await add.getByRole('button', { name: 'Добавить' }).click();
	// Сразу на страницу предмета – он в архиве, загружать в него можно как обычно.
	await expect(page.getByRole('heading', { level: 1 })).toContainText(subject);
	await expect(page.getByText('в архиве').first()).toBeVisible();
	const id = Number(new URL(page.url()).pathname.split('/').pop());
	const note = await api(page, 'POST', `/api/subjects/${id}/materials`, {
		kind: 'note',
		description: 'Билеты прошлого года'
	});
	expect(note.status).toBe(200);

	await page.goto('/subjects?archive=1');
	await expect(page.getByRole('region', { name: 'Весна e2e' })).toContainText(subject);
	expect(errors).toEqual([]);
});
