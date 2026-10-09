import { expect, test, type Page } from '@playwright/test';
import { ADMIN, login, watchConsole } from './helpers';

/** Бросить файл в любое место окна – как перетаскивание из Finder или проводника. */
async function dropFile(page: Page, name: string) {
	await page.evaluate((n) => {
		const dt = new DataTransfer();
		dt.items.add(new File(['%PDF-1.4\n'], n, { type: 'application/pdf' }));
		for (const type of ['dragenter', 'dragover', 'drop'])
			document.body.dispatchEvent(
				new DragEvent(type, { dataTransfer: dt, bubbles: true, cancelable: true })
			);
	}, name);
}

test('файлы: бросить в любое место окна и вставить – имя сохраняется', async ({ page }) => {
	const errors = watchConsole(page);
	await login(page, ADMIN);
	await page.goto('/subjects');
	await page.locator('main a[href^="/subjects/"]').first().click();
	await expect(page.getByRole('navigation', { name: 'Разделы предмета' })).toBeVisible();

	// Без открытой формы: бросили файл на страницу предмета – сразу окно материала этого предмета.
	await dropFile(page, 'Лекция 3 – сети.pdf');
	const dialog = page.getByRole('dialog', { name: 'Добавить материал' });
	await expect(dialog).toBeVisible();
	await expect(dialog.getByText('Лекция 3 – сети.pdf')).toBeVisible();

	// В открытой форме: ещё один файл брошен мимо поля и один вставлен из буфера.
	await dropFile(page, 'Конспект.pdf');
	await expect(dialog.getByText('Конспект.pdf')).toHaveCount(1);
	await page.evaluate(() => {
		const dt = new DataTransfer();
		dt.items.add(new File(['%PDF-1.4\n'], 'Билеты.pdf', { type: 'application/pdf' }));
		document.dispatchEvent(
			new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true })
		);
	});
	await expect(dialog.getByText('Билеты.pdf')).toHaveCount(1);
	await expect(dialog.getByRole('progressbar')).toHaveCount(0);
	await dialog.getByRole('button', { name: 'Добавить' }).click();
	await expect(dialog).toBeHidden();
	await page
		.getByRole('navigation', { name: 'Разделы предмета' })
		.getByRole('link', { name: 'Материалы' })
		.click();
	for (const n of ['Лекция 3 – сети', 'Конспект', 'Билеты'])
		await expect(page.locator('.mrow', { hasText: n }).first()).toBeVisible();
	expect(errors).toEqual([]);
});
