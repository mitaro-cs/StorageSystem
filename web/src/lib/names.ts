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

const TRANSLIT: Record<string, string> = {
	а: 'a',
	б: 'b',
	в: 'v',
	г: 'g',
	д: 'd',
	е: 'e',
	ё: 'e',
	ж: 'zh',
	з: 'z',
	и: 'i',
	й: 'y',
	к: 'k',
	л: 'l',
	м: 'm',
	н: 'n',
	о: 'o',
	п: 'p',
	р: 'r',
	с: 's',
	т: 't',
	у: 'u',
	ф: 'f',
	х: 'kh',
	ц: 'ts',
	ч: 'ch',
	ш: 'sh',
	щ: 'shch',
	ъ: '',
	ы: 'y',
	ь: '',
	э: 'e',
	ю: 'yu',
	я: 'ya'
};

/**
 * Логин из ФИО, как на сервере (accounts.Names.suggestUsername):
 * «Петров Иван Сергеевич» → «petrov.ivan». Пустая строка, если ничего не вышло.
 */
export function suggestUsername(fio: string): string {
	const parts = words(fio.toLowerCase())
		.slice(0, 2)
		.map((w) =>
			[...w].map((c) => (c in TRANSLIT ? TRANSLIT[c] : /[a-z0-9]/.test(c) ? c : '')).join('')
		)
		.filter(Boolean);
	const s = parts.join('.').slice(0, 28);
	return s.length < 3 ? '' : s;
}
