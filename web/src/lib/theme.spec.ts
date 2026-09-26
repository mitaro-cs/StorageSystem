import { describe, expect, it } from 'vitest';
import { STYLES, isStyle } from './theme';
import { PRESETS, accentVars, cssText, oklch } from './colors';

describe('дизайны оформления', () => {
	it('пять вариантов, первый — «Классика»; у каждого есть название и пояснение', () => {
		expect(STYLES).toHaveLength(5);
		expect(STYLES[0].id).toBe('plain');
		expect(STYLES[0].label).toBe('Классика');
		const ids = STYLES.map((s) => s.id);
		expect(new Set(ids).size).toBe(ids.length);
		for (const s of STYLES) expect(s.label && s.hint).toBeTruthy();
	});

	it('чужое или устаревшее значение из хранилища — не дизайн', () => {
		expect(isStyle('glass')).toBe(true);
		expect(isStyle('comic')).toBe(false);
		expect(isStyle('glass" onload="x')).toBe(false);
		expect(isStyle(undefined)).toBe(false);
	});
});

describe('основной цвет', () => {
	it('OKLCH переводится в #rrggbb, слишком яркое — в видимый цвет того же оттенка', () => {
		expect(oklch(1, 0, 0)).toBe('#ffffff');
		expect(oklch(0, 0, 0)).toBe('#000000');
		for (const h of [0, 90, 150, 200, 262, 330])
			expect(oklch(0.7, 0.4, h)).toMatch(/^#[0-9a-f]{6}$/);
	});

	it('«Чернила» — без переменных: классика как была', () => {
		expect(PRESETS[0].hue).toBeNull();
		expect(accentVars({ hue: null, sat: 60 })).toEqual({});
		expect(cssText({ hue: null, sat: 60 })).toBe('');
	});

	it('насыщенность делает кнопки и фон цветнее, а при нуле — серыми', () => {
		const chroma = (hex: string) => {
			const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));
			return Math.max(r, g, b) - Math.min(r, g, b);
		};
		const grey = accentVars({ hue: 150, sat: 0 });
		const vivid = accentVars({ hue: 150, sat: 100 });
		expect(chroma(vivid['--pal-accent'])).toBeGreaterThan(chroma(grey['--pal-accent']) + 40);
		expect(chroma(vivid['--pald-bg'])).toBeGreaterThan(chroma(grey['--pald-bg']));
		expect(chroma(grey['--pald-bg'])).toBeLessThanOrEqual(2);
	});

	it('строка для app.html — только переменные с цветами и числом оттенка', () => {
		const css = cssText({ hue: 262, sat: 70 });
		expect(css).toMatch(/^(--[a-z0-9-]+:[#0-9a-f]+;?)+$/);
		expect(css).toContain('--pal-accent:#');
		expect(css).toContain('--accent-h:262');
	});
});
