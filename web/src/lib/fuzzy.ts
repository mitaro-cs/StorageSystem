/** Нечёткое совпадение для палитры: все символы запроса по порядку; выше — у начала слов. */
export function score(query: string, text: string): number {
	const q = query.toLowerCase().replace(/ё/g, 'е').trim();
	const t = text.toLowerCase().replace(/ё/g, 'е');
	if (!q) return 1;
	const direct = t.indexOf(q);
	if (direct >= 0) return 100 - direct + (direct === 0 || t[direct - 1] === ' ' ? 50 : 0);
	let ti = 0;
	let s = 0;
	for (const ch of q) {
		if (ch === ' ') continue;
		const found = t.indexOf(ch, ti);
		if (found < 0) return 0;
		s += found === ti ? 3 : 1;
		if (found === 0 || t[found - 1] === ' ') s += 4;
		ti = found + 1;
	}
	return s;
}
