import { describe, expect, it } from 'vitest';
import { fioError, firstName, shortNames, suggestUsername } from './names';

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

describe('suggestUsername', () => {
	it.each([
		['Петров Иван Сергеевич', 'petrov.ivan'],
		['Щукина Юлия', 'shchukina.yuliya'],
		['  Ёжиков   Олег ', 'ezhikov.oleg'],
		["O'Brien Sean", 'obrien.sean'],
		['Я', '']
	])('%s → %s', (fio, login) => expect(suggestUsername(fio)).toBe(login));
});

describe('shortNames', () => {
	const p = (id: number, displayName: string) => ({ id, displayName });

	it('shows only the first name when it is unique', () => {
		const m = shortNames([p(1, 'Сарбашев Омар Русланович'), p(2, 'Иванова Анна Сергеевна')]);
		expect(m.get(1)).toBe('Омар');
		expect(m.get(2)).toBe('Анна');
	});

	it('adds the surname when first names match', () => {
		const m = shortNames([
			p(1, 'Петров Иван Сергеевич'),
			p(2, 'Сидоров Иван Олегович'),
			p(3, 'Козлова Мария')
		]);
		expect(m.get(1)).toBe('Иван Петров');
		expect(m.get(2)).toBe('Иван Сидоров');
		expect(m.get(3)).toBe('Мария');
	});

	it('treats ё and е as the same letter', () => {
		const m = shortNames([p(1, 'Орлов Артём'), p(2, 'Лисин Артем')]);
		expect(m.get(1)).toBe('Артём Орлов');
		expect(m.get(2)).toBe('Артем Лисин');
	});

	it('falls back to the patronymic initial for namesakes', () => {
		const m = shortNames([p(1, 'Петров Иван Сергеевич'), p(2, 'Петров Иван Олегович')]);
		expect(m.get(1)).toBe('Иван Петров С.');
		expect(m.get(2)).toBe('Иван Петров О.');
	});

	it('ignores repeated authors and deleted accounts', () => {
		const m = shortNames([
			p(1, 'Петров Иван'),
			p(1, 'Петров Иван'),
			{ id: 2, displayName: 'Сидоров Иван', deleted: true }
		]);
		expect(m.get(1)).toBe('Иван');
		expect(m.has(2)).toBe(false);
	});
});
