import { describe, expect, it } from 'vitest';
import { direction } from './transition';

describe('направление смены раздела', () => {
	it('по порядку нижней панели', () => {
		expect(direction('/', '/homework', false)).toBe('fwd');
		expect(direction('/schedule', '/news', false)).toBe('back');
		expect(direction('/homework', '/', false)).toBe('back');
	});
	it('вглубь и обратно', () => {
		expect(direction('/homework', '/homework/12', false)).toBe('fwd');
		expect(direction('/subjects/3', '/subjects', false)).toBe('back');
	});
	it('кнопка «назад» – всегда слева', () => {
		expect(direction('/', '/schedule', true)).toBe('back');
	});
	it('разделы вне панели – без сдвига', () => {
		expect(direction('/search', '/members', false)).toBe('fade');
	});
});
