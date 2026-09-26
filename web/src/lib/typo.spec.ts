import { describe, expect, it } from 'vitest';
import { typo } from './typo';

describe('typo', () => {
	it('короткие предлоги держатся за следующее слово', () => {
		expect(typo('С вопросами к старосте')).toBe('С вопросами к старосте');
		expect(typo('в метро и на паре')).toBe('в метро и на паре');
	});
	it('тире не начинает строку', () => {
		expect(typo('Сегодня — главное')).toBe('Сегодня — главное');
	});
	it('длинные слова не трогает', () => {
		expect(typo('Задания и новости')).toBe('Задания и новости');
		expect(typo('groupbase работает')).toBe('groupbase работает');
	});
});
