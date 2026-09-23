/** Русская форма множественного числа: plural(5, ['день', 'дня', 'дней']) → 'дней'. */
export function plural(n: number, forms: [string, string, string]): string {
	const abs = Math.abs(n) % 100;
	const last = abs % 10;
	if (abs > 10 && abs < 20) return forms[2];
	if (last > 1 && last < 5) return forms[1];
	if (last === 1) return forms[0];
	return forms[2];
}

const DAY = 24 * 60 * 60 * 1000;

function startOfDay(ms: number): number {
	const d = new Date(ms);
	d.setHours(0, 0, 0, 0);
	return d.getTime();
}

/** «сегодня», «завтра», «через 3 дня», «вчера», «2 дня назад» относительно now. */
export function relativeDay(target: number, now: number = Date.now()): string {
	const diff = Math.round((startOfDay(target) - startOfDay(now)) / DAY);
	if (diff === 0) return 'сегодня';
	if (diff === 1) return 'завтра';
	if (diff === -1) return 'вчера';
	if (diff > 1) return `через ${diff} ${plural(diff, ['день', 'дня', 'дней'])}`;
	return `${-diff} ${plural(-diff, ['день', 'дня', 'дней'])} назад`;
}
