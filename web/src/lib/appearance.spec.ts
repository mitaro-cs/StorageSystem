import { describe, expect, it } from 'vitest';
import { parseBackground } from './appearance';

describe('фон страниц входа', () => {
	it('встроенный рисунок', () => {
		expect(parseBackground('preset:night')).toEqual({ preset: 'night', image: null });
		expect(parseBackground(null)).toEqual({ preset: 'aurora', image: null });
		expect(parseBackground('preset:что-то')).toEqual({ preset: 'aurora', image: null });
	});

	it('своя картинка и без фона', () => {
		expect(parseBackground('image:0123456789abcdef0123').image).toBe(
			'/api/appearance/background/0123456789abcdef0123.webp'
		);
		expect(parseBackground('image:../../x').image).toBeNull();
		expect(parseBackground('none')).toEqual({ preset: null, image: null });
	});
});
