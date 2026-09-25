import { describe, expect, it } from 'vitest';
import { PALETTES, isPalette } from './theme';

describe('цветовые темы', () => {
	it('у каждой темы свой id, название и цвета для обоих режимов', () => {
		const ids = PALETTES.map((p) => p.id);
		expect(new Set(ids).size).toBe(ids.length);
		expect(ids[0]).toBe('classic');
		for (const p of PALETTES) {
			expect(p.label).not.toBe('');
			for (const c of [...p.light, ...p.dark]) expect(c).toMatch(/^#[0-9a-f]{6}$/);
		}
	});

	it('чужое значение из хранилища — не тема', () => {
		expect(isPalette('ocean')).toBe(true);
		expect(isPalette('<script>')).toBe(false);
		expect(isPalette(null)).toBe(false);
	});
});
