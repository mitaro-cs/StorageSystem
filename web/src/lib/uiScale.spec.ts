import { describe, expect, it } from 'vitest';
import { viewportFor } from './uiScale';

describe('viewportFor', () => {
	it('100 % – обычная ширина устройства', () => {
		expect(viewportFor(100, false, 390, 844)).toMatch(/^width=device-width, initial-scale=1,/);
	});
	it('мельче – страница шире экрана и уменьшена браузером', () => {
		const v = viewportFor(80, false, 390, 844);
		expect(v).toContain('width=488');
		expect(v).toContain('initial-scale=0.8');
		expect(v).toContain('maximum-scale=0.8');
	});
	it('крупнее и в альбомной ориентации – от длинной стороны', () => {
		const v = viewportFor(125, true, 390, 844);
		expect(v).toContain('width=675');
		expect(v).toContain('initial-scale=1.25');
	});
});
