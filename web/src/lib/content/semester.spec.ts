import { describe, expect, it } from 'vitest';
import { PALETTE, parseSubjects, pickColors } from './semester';

describe('новый семестр', () => {
	it('разбирает название и преподавателя через тире, дефис или «;»', () => {
		const list = parseSubjects(
			[
				'Физика — Иванов И. И.',
				'  2. Сети связи - Петрова А. А.',
				'• Английский язык; Smith J.',
				'',
				'Базы данных',
				'Теория вероятностей – Ким О.'
			].join('\n')
		);
		expect(list).toEqual([
			{ name: 'Физика', teacher: 'Иванов И. И.', duplicate: false },
			{ name: 'Сети связи', teacher: 'Петрова А. А.', duplicate: false },
			{ name: 'Английский язык', teacher: 'Smith J.', duplicate: false },
			{ name: 'Базы данных', teacher: '', duplicate: false },
			{ name: 'Теория вероятностей', teacher: 'Ким О.', duplicate: false }
		]);
	});

	it('дефис внутри названия не делит строку', () => {
		expect(parseSubjects('Физико-химия')[0]).toEqual({
			name: 'Физико-химия',
			teacher: '',
			duplicate: false
		});
	});

	it('повторы в списке убирает, а уже существующие помечает', () => {
		const list = parseSubjects('Физика\nфизика\nЁмкости и ток', ['ФИЗИКА ', 'Емкости и ток']);
		expect(list.map((s) => [s.name, s.duplicate])).toEqual([
			['Физика', true],
			['Ёмкости и ток', true]
		]);
	});

	it('цвета: сначала свободные, потом по кругу', () => {
		const taken = [PALETTE[0], PALETTE[1]];
		const colors = pickColors(9, taken);
		expect(colors.slice(0, 7)).not.toContain(PALETTE[0]);
		expect(colors[7]).toBe(PALETTE[0]);
		expect(pickColors(20, []).length).toBe(20);
	});
});
