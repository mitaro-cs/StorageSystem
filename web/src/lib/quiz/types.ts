// Тесты онлайн (0.9.7): то же, что QuizService на сервере.

export type QuestionKind = 'single' | 'multi' | 'text';

/** Вопрос целиком – у тех, кто ведёт предмет. */
export interface Question {
	kind: QuestionKind;
	text: string;
	options: string[];
	correct: number[];
	accepted: string[];
	points: number;
}

/** Вопрос для проходящего – без ответов. */
export interface Ask {
	kind: QuestionKind;
	text: string;
	options: string[];
	points: number;
}

export interface Answer {
	choices?: number[];
	text?: string;
}

export interface QuizItem {
	id: number;
	subjectId: number;
	title: string;
	description: string;
	questions: number;
	points: number;
	timeLimit: number | null;
	attempts: number;
	showAnswers: boolean;
	published: boolean;
	closesAt: number | null;
	createdAt: number;
	mine: {
		used: number;
		best: number | null;
		max: number | null;
		open: number | null;
		last: number | null;
	};
	stats: { people: number; average: number | null } | null;
	can: { edit: boolean };
}

export interface QuizDetail {
	quiz: QuizItem;
	questions: Question[] | null;
	asks: Ask[];
}

export interface Attempt {
	id: number;
	quizId: number;
	title: string;
	startedAt: number;
	deadline: number | null;
	questions: Ask[];
}

export interface Mark {
	right: boolean;
	points: number;
	max: number;
	correct: number[] | null;
	accepted: string[] | null;
}

export interface QuizResult {
	id: number;
	quizId: number;
	title: string;
	startedAt: number;
	finishedAt: number;
	score: number;
	max: number;
	questions: Ask[];
	answers: Answer[];
	marks: Mark[];
}

export interface ResultRow {
	attemptId: number;
	userId: number;
	name: string;
	startedAt: number;
	finishedAt: number | null;
	score: number | null;
	max: number | null;
}

export const KIND_LABEL: Record<QuestionKind, string> = {
	single: 'Один ответ',
	multi: 'Несколько ответов',
	text: 'Ответ словом'
};

/** «7 из 10 (70 %)» */
export function scoreText(score: number, max: number): string {
	const n = (x: number) => (Number.isInteger(x) ? String(x) : x.toFixed(1).replace('.', ','));
	const pct = max > 0 ? Math.round((score / max) * 100) : 0;
	return `${n(score)} из ${n(max)} (${pct} %)`;
}
