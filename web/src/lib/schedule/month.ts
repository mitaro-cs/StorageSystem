// Подписи в клетках «Месяца» – только для MonthView (грузится по выбору вида).

/** «ВМ» из «Высшая математика», «Физ» из «Физика» – подписи в клетке месяца. */
export function abbr(name: string): string {
	const words = name
		.replace(/[«»"()№\d.,]/g, ' ')
		.split(/[\s-]+/)
		.filter((w) => w.length > 2 || /^[А-ЯЁA-Z]/.test(w));
	if (words.length >= 2)
		return words
			.slice(0, 4)
			.map((w) => w[0].toUpperCase())
			.join('');
	const w = words[0] ?? name.trim();
	return w.charAt(0).toUpperCase() + w.slice(1, 3);
}
