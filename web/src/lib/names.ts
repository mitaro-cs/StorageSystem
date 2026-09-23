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

/** Фамилия из ФИО — первое слово. */
export function lastName(fio: string): string {
	const w = words(fio);
	return w.length > 1 ? w[0] : '';
}

const norm = (s: string) => s.toLowerCase().replaceAll('ё', 'е');

/**
 * Короткие подписи людей в обсуждении: только имя, а если имя встречается у разных людей — имя и
 * фамилия (дальше — инициал отчества, в крайнем случае ФИО целиком).
 */
export function shortNames(
	people: { id: number; displayName: string; deleted?: boolean }[]
): Map<number, string> {
	const fio = new Map<number, string>();
	for (const p of people) if (!p.deleted && p.displayName) fio.set(p.id, p.displayName);
	const label = (f: string, level: number): string => {
		const w = words(f);
		const first = firstName(f);
		const last = lastName(f);
		if (level === 0) return first;
		if (level === 1) return last ? `${first} ${last}` : f;
		if (level === 2 && w.length > 2) return `${first} ${last} ${w[2][0]}.`;
		return w.join(' ');
	};
	const level = new Map<number, number>([...fio.keys()].map((id) => [id, 0]));
	for (let round = 0; round < 3; round++) {
		const byLabel = new Map<string, number[]>();
		for (const [id, f] of fio) {
			const key = norm(label(f, level.get(id)!));
			byLabel.set(key, [...(byLabel.get(key) ?? []), id]);
		}
		let changed = false;
		for (const ids of byLabel.values()) {
			if (ids.length < 2) continue;
			for (const id of ids) {
				level.set(id, level.get(id)! + 1);
				changed = true;
			}
		}
		if (!changed) break;
	}
	return new Map([...fio].map(([id, f]) => [id, label(f, level.get(id)!)]));
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
