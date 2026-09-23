import { describe, expect, it } from 'vitest';
import { parseNames } from './parseNames';

describe('parseNames', () => {
	it('понимает столбец имён', () => {
		expect(parseNames('Иван Петров\n\n  Анна Смирнова  \n')).toEqual([
			{ displayName: 'Иван Петров' },
			{ displayName: 'Анна Смирнова' }
		]);
	});
	it('понимает CSV с заголовком', () => {
		expect(parseNames('username,display_name\nivan.p,Иван Петров\n"anna";"Анна"')).toEqual([
			{ username: 'ivan.p', displayName: 'Иван Петров' },
			{ username: 'anna', displayName: 'Анна' }
		]);
	});
	it('пропускает заголовок «ФИО»', () => {
		expect(parseNames('ФИО\nОлег Ким')).toEqual([{ displayName: 'Олег Ким' }]);
	});
});
