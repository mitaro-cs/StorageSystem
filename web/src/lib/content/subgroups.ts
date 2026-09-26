import type { Subject } from '$lib/types';

/**
 * Предметы по подгруппам: «Английский язык №1 Сильная группа» и «Английский язык №2 Слабая
 * группа» — один предмет для двух половин группы. Каждый выбирает свою, и задания, новости и
 * уведомления другой подгруппы ему не мешают (на сервере — «не мой предмет», subject_hidden).
 */

/**
 * Номер подгруппы: «№1», «1 подгруппа», «подгруппа 2», «2 гр.», «(1)». Перед ним — не буква и не
 * цифра (\b в JS понимает только латиницу, а просмотр назад есть не во всех Safari).
 */
const MARK =
	/(^|[^\p{L}\d])(№\s*\d+|\d+\s*(?:-?(?:я|ая)\s*)?(?:под)?гр(?:уппа|\.)?(?=[\s.,)]|$)|(?:под)?группа\s*№?\s*\d+|\(\s*\d+\s*\))/iu;

/** С какого знака начинается номер подгруппы; -1 — номера нет. */
function markAt(name: string): number {
	const m = MARK.exec(name);
	return m ? m.index + m[1].length : -1;
}

/** Общее название без номера подгруппы («английский язык») или null — номера нет. */
export function baseName(name: string): string | null {
	const at = markAt(name);
	if (at <= 0) return null;
	const base = name
		.slice(0, at)
		.replace(/[\s,.:;—–-]+$/u, '')
		.trim();
	return base ? base.toLowerCase().replaceAll('ё', 'е').replace(/\s+/g, ' ') : null;
}

export interface Choice {
	/** Ключ для «хожу на все»: общее название и номера предметов. */
	key: string;
	/** «Английский Язык» — как написано у первого предмета. */
	title: string;
	options: { subject: Subject; label: string }[];
}

/** Наборы предметов по подгруппам: два и больше предмета с одним общим названием. */
export function choices(list: Subject[]): Choice[] {
	const by = new Map<string, Subject[]>();
	for (const s of list) {
		if (s.archived) continue;
		const base = baseName(s.name);
		if (base) by.set(base, [...(by.get(base) ?? []), s]);
	}
	const out: Choice[] = [];
	for (const [base, subjects] of by) {
		if (subjects.length < 2) continue;
		subjects.sort((a, b) => a.name.localeCompare(b.name, 'ru', { numeric: true }));
		const first = subjects[0].name;
		const cut = markAt(first);
		out.push({
			key: base + ':' + subjects.map((s) => s.id).join(','),
			title: first.slice(0, cut).replace(/[\s,.:;—–-]+$/u, ''),
			options: subjects.map((s) => {
				const at = Math.max(0, markAt(s.name));
				return { subject: s, label: s.name.slice(at).trim() || s.name };
			})
		});
	}
	return out;
}

const OK_KEY = 'gb-subgroups-ok';

/** «Хожу на все» — больше не спрашиваем на этом устройстве. */
export function dismissed(): string[] {
	try {
		const v = JSON.parse(localStorage.getItem(OK_KEY) ?? '[]');
		return Array.isArray(v) ? v.filter((x) => typeof x === 'string') : [];
	} catch {
		return [];
	}
}

export function dismiss(key: string) {
	try {
		localStorage.setItem(OK_KEY, JSON.stringify([...dismissed(), key].slice(-50)));
	} catch {
		/* приватный режим — спросим ещё раз */
	}
}

/** Ещё не выбрано: все предметы набора — «мои», и «хожу на все» не нажимали. */
export function open(c: Choice, skip: string[] = dismissed()): boolean {
	return !skip.includes(c.key) && c.options.every((o) => o.subject.mine !== false);
}
