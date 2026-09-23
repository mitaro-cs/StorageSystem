import { describe, expect, it } from 'vitest';
import { fmtDue, fmtSize, hueFor, initials, plural, relativeDay } from './format';

describe('plural', () => {
	const forms: [string, string, string] = ['файл', 'файла', 'файлов'];
	it.each([
		[1, 'файл'],
		[2, 'файла'],
		[5, 'файлов'],
		[11, 'файлов'],
		[21, 'файл'],
		[112, 'файлов'],
		[104, 'файла']
	])('%i → %s', (n, expected) => {
		expect(plural(n, forms)).toBe(expected);
	});
});

describe('relativeDay', () => {
	const now = new Date(2026, 8, 23, 15, 0).getTime();
	it('понимает соседние дни', () => {
		expect(relativeDay(new Date(2026, 8, 23, 9, 0).getTime(), now)).toBe('сегодня');
		expect(relativeDay(new Date(2026, 8, 24, 0, 1).getTime(), now)).toBe('завтра');
		expect(relativeDay(new Date(2026, 8, 22, 23, 59).getTime(), now)).toBe('вчера');
	});
	it('считает дни в обе стороны', () => {
		expect(relativeDay(new Date(2026, 8, 28).getTime(), now)).toBe('через 5 дней');
		expect(relativeDay(new Date(2026, 8, 20).getTime(), now)).toBe('3 дня назад');
	});
});

describe('initials', () => {
	it('берёт первые буквы двух слов', () => {
		expect(initials('Иван Петров')).toBe('ИП');
		expect(initials('  анна ')).toBe('А');
		expect(initials('')).toBe('?');
	});
});

describe('hueFor', () => {
	it('стабилен и в диапазоне', () => {
		expect(hueFor(42)).toBe(hueFor(42));
		expect(hueFor(7)).toBeGreaterThanOrEqual(0);
		expect(hueFor(7)).toBeLessThan(360);
	});
});

describe('fmtDue', () => {
	it('для близких дат пишет относительный день', () => {
		const now = new Date(2026, 8, 23, 12, 0).getTime();
		expect(fmtDue(new Date(2026, 8, 24, 23, 59).getTime(), now)).toBe('завтра, 23:59');
	});
});

describe('fmtSize', () => {
	it('подбирает единицы', () => {
		expect(fmtSize(12)).toBe('12 Б');
		expect(fmtSize(1536)).toBe('1,5 КБ');
		expect(fmtSize(5 * 1024 * 1024)).toBe('5 МБ');
	});
});
