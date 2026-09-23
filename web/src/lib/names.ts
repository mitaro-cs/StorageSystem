// ФИО хранится одной строкой в порядке «Фамилия Имя Отчество». Проверка повторяет серверную
// (accounts.Names.displayName), чтобы ошибка показывалась сразу, без запроса.

const WORD = /^\p{L}[\p{L}\p{M}'’.-]*$/u;

function words(fio: string): string[] {
	const s = fio.trim();
	return s ? s.split(/\s+/) : [];
}

/** Имя из ФИО — второе слово («Сарбашев Омар Русланович» → «Омар»). */
export function firstName(fio: string): string {
	const w = words(fio);
	return w[1] ?? w[0] ?? '';
}

/** Текст ошибки или пустая строка, если ФИО записано верно. */
export function fioError(fio: string): string {
	const w = words(fio);
	if (w.join(' ').length > 64) return 'ФИО — не длиннее 64 символов';
	if (w.length < 2 || w.length > 5) return 'Укажите ФИО: фамилию, имя и отчество (если есть)';
	if (!w.every((x) => WORD.test(x))) return 'ФИО пишется буквами, например: Иванов Иван Иванович';
	return '';
}
