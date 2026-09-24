/**
 * Тип задания (homework.kind на сервере). Домашнее — по умолчанию и без метки в списках;
 * у зачёта и экзамена срок — это «когда», и у них есть место (аудитория).
 */
export type HomeworkKind = 'homework' | 'lab' | 'test' | 'credit' | 'exam';

export interface KindInfo {
	value: HomeworkKind;
	label: string;
	/** Во множественном числе — для фильтров: «Экзамены». */
	many: string;
	/** Подсказка в поле названия. */
	placeholder: string;
}

export const KINDS: KindInfo[] = [
	{ value: 'homework', label: 'Домашнее', many: 'Домашние', placeholder: 'Задачи №412–420' },
	{ value: 'lab', label: 'Лабораторная', many: 'Лабораторные', placeholder: 'Лабораторная №3' },
	{
		value: 'test',
		label: 'Контрольная',
		many: 'Контрольные',
		placeholder: 'Контрольная: кинематика'
	},
	{ value: 'credit', label: 'Зачёт', many: 'Зачёты', placeholder: 'Зачёт по истории' },
	{ value: 'exam', label: 'Экзамен', many: 'Экзамены', placeholder: 'Экзамен по высшей математике' }
];

export function kindOf(v: string | null | undefined): KindInfo {
	return KINDS.find((k) => k.value === v) ?? KINDS[0];
}

/** Зачёт и экзамен: срок — время начала, есть место, входят в сессию. */
export function isExam(v: string | null | undefined): boolean {
	return v === 'credit' || v === 'exam';
}
