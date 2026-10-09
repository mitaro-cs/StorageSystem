// Разбор и показ дат для полей ввода (0.9.8): значения – как у системных полей ('YYYY-MM-DD',
// 'HH:MM'), а люди видят и печатают «12.10.2026» и «09:30».

const pad = (n: number) => String(n).padStart(2, '0');

export const isoDay = (y: number, m: number, d: number) => `${y}-${pad(m + 1)}-${pad(d)}`;

/** 'YYYY-MM-DD' → «12.10.2026»; пусто – пусто. */
export function showDay(iso: string): string {
	const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
	return m ? `${m[3]}.${m[2]}.${m[1]}` : '';
}

/** Только цифры с точками по мере ввода: «1210» → «12.10». */
export function maskDay(raw: string): string {
	const d = raw.replace(/\D/g, '').slice(0, 8);
	return [d.slice(0, 2), d.slice(2, 4), d.slice(4)].filter(Boolean).join('.');
}

/** «12.10.2026» (или уже 'YYYY-MM-DD') → 'YYYY-MM-DD'; несуществующий день – null. */
export function parseDay(text: string): string | null {
	const t = text.trim();
	if (/^\d{4}-\d{2}-\d{2}$/.test(t)) return showDay(t) ? t : null;
	const m = /^(\d{1,2})\.(\d{1,2})\.(\d{4})$/.exec(t);
	if (!m) return null;
	const [d, mo, y] = [Number(m[1]), Number(m[2]), Number(m[3])];
	const date = new Date(y, mo - 1, d);
	if (date.getFullYear() !== y || date.getMonth() !== mo - 1 || date.getDate() !== d) return null;
	return isoDay(y, mo - 1, d);
}

/** «0930» → «09:30». */
export function maskTime(raw: string): string {
	const d = raw.replace(/\D/g, '').slice(0, 4);
	return d.length > 2 ? `${d.slice(0, 2)}:${d.slice(2)}` : d;
}

/** «9:30», «0930», «09:30» → '09:30'; неверное – null. */
export function parseTime(text: string): string | null {
	const t = text.trim();
	const m = /^(\d{1,2}):?(\d{2})$/.exec(t);
	if (!m) return null;
	const [h, min] = [Number(m[1]), Number(m[2])];
	return h < 24 && min < 60 ? `${pad(h)}:${pad(min)}` : null;
}

/** Недели месяца с понедельника: дни соседних месяцев – null. */
export function monthGrid(year: number, month: number): (number | null)[][] {
	const first = (new Date(year, month, 1).getDay() + 6) % 7;
	const days = new Date(year, month + 1, 0).getDate();
	const cells: (number | null)[] = [...Array(first).fill(null)];
	for (let d = 1; d <= days; d++) cells.push(d);
	while (cells.length % 7) cells.push(null);
	const weeks: (number | null)[][] = [];
	for (let i = 0; i < cells.length; i += 7) weeks.push(cells.slice(i, i + 7));
	return weeks;
}

const MONTHS = [
	'Январь',
	'Февраль',
	'Март',
	'Апрель',
	'Май',
	'Июнь',
	'Июль',
	'Август',
	'Сентябрь',
	'Октябрь',
	'Ноябрь',
	'Декабрь'
];
export const monthTitle = (y: number, m: number) => `${MONTHS[m]} ${y}`;
