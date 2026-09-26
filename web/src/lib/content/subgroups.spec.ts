import { describe, expect, it } from 'vitest';
import type { Subject } from '$lib/types';
import { baseName, choices, open } from './subgroups';

const subject = (id: number, name: string, mine = true): Subject => ({
	id,
	name,
	teacher: '',
	color: '#000000',
	avatar: null,
	icon: null,
	chatUrl: null,
	archived: false,
	pinned: false,
	mine,
	groups: [{ id: 1, name: 'БИН2509' }],
	can: { edit: false, share: false }
});

describe('подгруппы', () => {
	it('общее название — без номера подгруппы', () => {
		expect(baseName('Английский Язык №1 Сильная Группа')).toBe('английский язык');
		expect(baseName('Английский язык №2 слабая группа')).toBe('английский язык');
		expect(baseName('Физика, 1 подгруппа')).toBe('физика');
		expect(baseName('Физика (2)')).toBe('физика');
		expect(baseName('Программирование — подгруппа 2')).toBe('программирование');
		expect(baseName('Высшая Математика')).toBeNull();
		expect(baseName('№5 Лабораторная')).toBeNull();
	});

	it('два предмета с одним названием — выбор подгруппы', () => {
		const list = [
			subject(1, 'Английский Язык №2 Слабая Группа'),
			subject(2, 'Высшая Математика'),
			subject(3, 'Английский Язык №1 Сильная Группа')
		];
		const [c, ...rest] = choices(list);
		expect(rest).toHaveLength(0);
		expect(c.title).toBe('Английский Язык');
		expect(c.options.map((o) => o.label)).toEqual(['№1 Сильная Группа', '№2 Слабая Группа']);
		expect(open(c, [])).toBe(true);
		expect(open(c, [c.key])).toBe(false);
	});

	it('выбрали подгруппу — второй предмет скрыт, больше не спрашиваем', () => {
		const list = [subject(1, 'Английский №1'), subject(3, 'Английский №2', false)];
		expect(open(choices(list)[0], [])).toBe(false);
	});

	it('один предмет с номером — не выбор', () => {
		expect(choices([subject(1, 'Английский №1'), subject(2, 'Физика')])).toEqual([]);
	});
});
