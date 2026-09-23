import { describe, expect, it } from 'vitest';
import { score } from './fuzzy';

describe('score', () => {
	it('находит подстроку и начало слова выше', () => {
		expect(score('мат', 'Математический анализ')).toBeGreaterThan(
			score('мат', 'Дискретная математика')
		);
	});
	it('находит по первым буквам слов', () => {
		expect(score('ма', 'Математический анализ')).toBeGreaterThan(0);
		expect(score('матан', 'Математический анализ')).toBeGreaterThan(0);
	});
	it('ё и е равны, лишнее не находит', () => {
		expect(score('ежик', 'Ёжик в тумане')).toBeGreaterThan(0);
		expect(score('физика', 'Математический анализ')).toBe(0);
	});
});
