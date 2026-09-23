import { describe, expect, it } from 'vitest';
import { fioError, firstName } from './names';

describe('firstName', () => {
	it.each([
		['Сарбашев Омар Русланович', 'Омар'],
		['  Ким   Олег ', 'Олег'],
		['Иванова-Петрова Анна-Мария', 'Анна-Мария'],
		['Омар', 'Омар'],
		['', '']
	])('%s → %s', (fio, name) => expect(firstName(fio)).toBe(name));
});

describe('fioError', () => {
	it('принимает фамилию и имя, отчество необязательно', () => {
		expect(fioError('Сарбашев Омар Русланович')).toBe('');
		expect(fioError('Ким Олег')).toBe('');
		expect(fioError('Алиев Рустам Ильхам оглы')).toBe('');
		expect(fioError("O'Brien Sean")).toBe('');
	});
	it('отклоняет одно слово, цифры и слишком длинное', () => {
		expect(fioError('Омар')).toMatch(/фамилию, имя/);
		expect(fioError('   ')).toMatch(/фамилию, имя/);
		expect(fioError('Тест u123')).toMatch(/буквами/);
		expect(fioError('А Б В Г Д Е')).toMatch(/фамилию, имя/);
		expect(fioError('Петров ' + 'И'.repeat(60))).toMatch(/64/);
	});
});
