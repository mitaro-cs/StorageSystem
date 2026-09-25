import { describe, expect, it } from 'vitest';
import { placeMenu } from './menuPlace';

const screen = { width: 390, height: 844 };
const menu = { width: 200, height: 180 };

describe('выпадающее меню', () => {
	it('под кнопкой, по её правому краю', () => {
		expect(placeMenu({ top: 100, bottom: 132, right: 370 }, menu, screen)).toEqual({
			top: 136,
			left: 170,
			up: false
		});
	});

	it('у нижнего края экрана раскрывается вверх — «Сделать замом» не пропадает', () => {
		const p = placeMenu({ top: 780, bottom: 812, right: 370 }, menu, screen);
		expect(p.up).toBe(true);
		expect(p.top + menu.height).toBeLessThanOrEqual(780);
	});

	it('не вылезает за левый край и за экран, даже если места нет ни сверху, ни снизу', () => {
		const p = placeMenu({ top: 40, bottom: 72, right: 60 }, menu, { width: 390, height: 200 });
		expect(p.left).toBe(8);
		expect(p.top).toBeGreaterThanOrEqual(8);
		expect(p.top + menu.height).toBeLessThanOrEqual(200 - 8 + 0.001);
	});
});
