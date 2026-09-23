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

export function startOfDay(ms: number): number {
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

const time = new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit' });
const dayMonth = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long' });
const dayMonthYear = new Intl.DateTimeFormat('ru-RU', {
	day: 'numeric',
	month: 'long',
	year: 'numeric'
});
const weekday = new Intl.DateTimeFormat('ru-RU', { weekday: 'long' });
const weekdayShort = new Intl.DateTimeFormat('ru-RU', { weekday: 'short' });

export const fmtTime = (ms: number) => time.format(ms);
export const fmtWeekday = (ms: number) => weekday.format(ms);
export const fmtWeekdayShort = (ms: number) => weekdayShort.format(ms);

/** «23 сентября» (год — только если не текущий). */
export function fmtDate(ms: number, now: number = Date.now()): string {
	return new Date(ms).getFullYear() === new Date(now).getFullYear()
		? dayMonth.format(ms)
		: dayMonthYear.format(ms);
}

/** Для ленты: «5 мин назад», «сегодня в 14:05», «вчера в 9:10», «12 сентября». */
export function fmtAgo(ms: number, now: number = Date.now()): string {
	const diff = now - ms;
	if (diff < 60_000) return 'только что';
	if (diff < 3_600_000) {
		const m = Math.floor(diff / 60_000);
		return `${m} ${plural(m, ['минуту', 'минуты', 'минут'])} назад`;
	}
	const rel = relativeDay(ms, now);
	if (rel === 'сегодня' || rel === 'вчера') return `${rel} в ${fmtTime(ms)}`;
	return fmtDate(ms, now);
}

/** Дедлайн: «завтра, 23:59», «пт, 26 сентября, 10:00». */
export function fmtDue(ms: number, now: number = Date.now()): string {
	const rel = relativeDay(ms, now);
	if (rel === 'сегодня' || rel === 'завтра' || rel === 'вчера') return `${rel}, ${fmtTime(ms)}`;
	return `${fmtWeekdayShort(ms)}, ${fmtDate(ms, now)}, ${fmtTime(ms)}`;
}

/** Значение для <input type="datetime-local"> в локальном времени. */
export function toLocalInput(ms: number): string {
	const d = new Date(ms);
	const p = (n: number) => String(n).padStart(2, '0');
	return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

export function fromLocalInput(v: string): number {
	return new Date(v).getTime();
}

/** Инициалы для аватара: «Иван Петров» → «ИП». */
export function initials(name: string): string {
	const parts = name.trim().split(/\s+/).filter(Boolean);
	if (parts.length === 0) return '?';
	return (parts[0][0] + (parts[1]?.[0] ?? '')).toUpperCase();
}

/** Детерминированный оттенок по id (для аватара без фото). */
export function hueFor(id: number): number {
	let x = (id * 2654435761) >>> 0;
	x ^= x >>> 15;
	return x % 360;
}
