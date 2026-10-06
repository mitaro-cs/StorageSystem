import { describe, expect, it } from 'vitest';
import { hotkey } from './platform';

const key = (key: string, code: string, shiftKey = false) => hotkey({ key, code, shiftKey });

describe('горячие клавиши при любой раскладке', () => {
	it('латиница – как есть, заглавная – строчной', () => {
		expect(key('k', 'KeyK')).toBe('k');
		expect(key('K', 'KeyK', true)).toBe('k');
	});

	it('русская раскладка: «л» на месте K – это k (Ctrl K открывает палитру)', () => {
		expect(key('л', 'KeyK')).toBe('k');
		expect(key('п', 'KeyG')).toBe('g');
		expect(key('т', 'KeyN')).toBe('n');
	});

	it('«/» и «?» – по своей клавише и по символу', () => {
		expect(key('/', 'Slash')).toBe('/');
		expect(key('.', 'Slash')).toBe('/');
		expect(key(',', 'Slash', true)).toBe('?');
		expect(key('?', 'Digit7', true)).toBe('?');
	});

	it('Dvorak: буква по символу, а не по месту клавиши', () => {
		expect(key('k', 'KeyV')).toBe('k');
	});

	it('служебные клавиши не трогаем', () => {
		expect(key('Escape', 'Escape')).toBe('Escape');
		expect(key('ArrowDown', 'ArrowDown')).toBe('ArrowDown');
	});
});
