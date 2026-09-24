/** Сложность задания: 1 — легко, 2 — средне, 3 — сложно. */
export const DIFFICULTY = [
	{ value: 1, label: 'Легко', hint: 'на вечер', tone: 'ok' },
	{ value: 2, label: 'Средне', hint: 'пара вечеров', tone: 'amber' },
	{ value: 3, label: 'Сложно', hint: 'начать заранее', tone: 'danger' }
] as const;

export type Difficulty = 1 | 2 | 3;

export function difficultyOf(v: number | null | undefined) {
	return DIFFICULTY.find((d) => d.value === v) ?? null;
}
