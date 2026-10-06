// Проверка ФИО для форм (регистрация, профиль, добавление людей) – отдельно от names.ts, который
// нужен на каждой странице: так проверка не грузится вместе с оболочкой.
import { words } from './names';

const WORD = /^\p{L}[\p{L}\p{M}'’.-]*$/u;

/** Слово начинается со строчной буквы (у букв без регистра – не ошибка). */
const lower = (w: string) => {
	const c = w.charAt(0);
	return c !== c.toUpperCase() && c === c.toLowerCase();
};

/** Ошибка одного поля ФИО: начинается с большой буквы («Ильхам оглы» – можно). */
export function capitalError(part: string): string {
	const w = words(part);
	return w.length && lower(w[0]) ? 'С большой буквы' : '';
}

/** Текст ошибки или пустая строка, если ФИО записано верно. */
export function fioError(fio: string): string {
	const w = words(fio);
	if (w.join(' ').length > 64) return 'ФИО – не длиннее 64 символов';
	if (w.length < 2 || w.length > 5) return 'Укажите ФИО: фамилию, имя и отчество (если есть)';
	if (!w.every((x) => WORD.test(x))) return 'ФИО пишется буквами, например: Иванов Иван Иванович';
	// Фамилия, имя и отчество – с большой буквы; дальше бывают «оглы», «кызы».
	if (w.slice(0, 3).some(lower)) return 'ФИО пишется с большой буквы: Иванов Иван Иванович';
	return '';
}
