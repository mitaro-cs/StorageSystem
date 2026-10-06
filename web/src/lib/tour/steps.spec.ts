import { describe, expect, it } from 'vitest';
import { placeCard, tourSteps } from './steps';

const view = { width: 1280, height: 800 };
const card = { width: 340, height: 180 };

describe('тур', () => {
	it('студенту – пять подсказок, старосте и модератору – ещё по одной', () => {
		expect(tourSteps({ manage: false, moderate: false, phone: false })).toHaveLength(5);
		const all = tourSteps({ manage: true, moderate: true, phone: true }).map((s) => s.id);
		expect(all.slice(-2)).toEqual(['manage', 'moderate']);
	});

	it('на телефоне про поиск – без сочетания клавиш', () => {
		const search = tourSteps({ manage: false, moderate: false, phone: true }).find(
			(s) => s.id === 'search'
		);
		expect(search?.text).not.toContain('Ctrl');
	});

	it('цель в боковой панели – карточка справа от неё', () => {
		const p = placeCard({ top: 300, left: 16, width: 240, height: 44 }, card, view);
		expect(p.left).toBe(16 + 240 + 14);
		expect(p.top).toBe(300 + 22 - 90);
	});

	it('у нижнего края – над целью, и никогда за экраном', () => {
		const phone = { width: 390, height: 844 };
		const p = placeCard({ top: 770, left: 200, width: 46, height: 46 }, card, phone);
		expect(p.top + card.height).toBeLessThanOrEqual(770);
		expect(p.left).toBeGreaterThanOrEqual(12);
		expect(p.left + card.width).toBeLessThanOrEqual(phone.width - 12 + 0.001);
	});

	it('без цели – по центру', () => {
		expect(placeCard(null, card, view)).toEqual({ top: 310, left: 470 });
	});
});
