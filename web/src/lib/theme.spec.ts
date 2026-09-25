import { describe, expect, it } from 'vitest';
import { PALETTES, STYLES, isPalette, isStyle } from './theme';

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

describe('стили оформления', () => {
	it('первый — «Обычный», как было; у каждого есть название и пояснение', () => {
		expect(STYLES[0].id).toBe('plain');
		const ids = STYLES.map((s) => s.id);
		expect(new Set(ids).size).toBe(ids.length);
		for (const s of STYLES) expect(s.label && s.hint).toBeTruthy();
	});

	it('чужое значение из хранилища — не стиль', () => {
		expect(isStyle('glass')).toBe(true);
		expect(isStyle('glass" onload="x')).toBe(false);
		expect(isStyle(undefined)).toBe(false);
	});
});
