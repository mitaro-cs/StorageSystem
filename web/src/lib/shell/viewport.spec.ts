import { describe, expect, it } from 'vitest';
import { typingIn, viewportShift } from './viewport';

const normal = { offsetTop: 0, scale: 1, fixedBottom: 800, screenHeight: 800 };

describe('viewportShift — нижняя панель на iPhone после клавиатуры', () => {
	it('всё в порядке — не двигаем', () => {
		expect(viewportShift(normal)).toBe(0);
		expect(viewportShift({ ...normal, offsetTop: 0.4, fixedBottom: 800.2 })).toBe(0);
	});

	it('видимая часть съехала вниз (iOS 26): опускаем панель на столько же', () => {
		expect(viewportShift({ ...normal, offsetTop: 68 })).toBe(68);
	});

	it('раскладка осталась короче экрана (iOS 27): опускаем до края', () => {
		expect(viewportShift({ ...normal, fixedBottom: 732 })).toBe(68);
		expect(viewportShift({ ...normal, fixedBottom: 760, offsetTop: 12 })).toBe(52);
	});

	it('при увеличении пальцами и без замера высоты — не трогаем', () => {
		expect(viewportShift({ ...normal, offsetTop: 68, scale: 1.6 })).toBe(0);
		expect(viewportShift({ ...normal, offsetTop: 68, screenHeight: 0 })).toBe(0);
	});

	it('слишком большой сдвиг — это клавиатура или поворот, не ошибка', () => {
		expect(viewportShift({ ...normal, fixedBottom: 420 })).toBe(0);
	});
});

describe('typingIn — открыта ли клавиатура', () => {
	const el = (tagName: string, extra: Record<string, unknown> = {}) =>
		({ tagName, isContentEditable: false, ...extra }) as unknown as Element;

	it('текстовые поля — да', () => {
		expect(typingIn(el('INPUT', { type: 'text' }))).toBe(true);
		expect(typingIn(el('INPUT', { type: 'password' }))).toBe(true);
		expect(typingIn(el('TEXTAREA'))).toBe(true);
		expect(typingIn(el('DIV', { isContentEditable: true }))).toBe(true);
	});

	it('кнопки, флажки и ничего — нет', () => {
		expect(typingIn(el('INPUT', { type: 'checkbox' }))).toBe(false);
		expect(typingIn(el('INPUT', { type: 'file' }))).toBe(false);
		expect(typingIn(el('BUTTON'))).toBe(false);
		expect(typingIn(null)).toBe(false);
	});
});
