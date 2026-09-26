/**
 * Цвет интерфейса (Настройки → Оформление): основной цвет (оттенок) и насыщенность. Из них
 * считаются все цвета темы — кнопки, активные пункты, фон страницы и карточек в тёмном режиме — в
 * пространстве OKLCH: одинаковая яркость у любого оттенка, поэтому текст на кнопке читается всегда.
 * Готовые переменные CSS хранятся строкой (gb-accent-css) — скрипт в app.html ставит их до
 * отрисовки, без этого модуля.
 */

export interface Accent {
	/** Оттенок 0–360; null — «Чернила»: чёрно-белая классика без цвета. */
	hue: number | null;
	/** Насыщенность 0–100: насколько цветными будут кнопки и фон. */
	sat: number;
}

export const DEFAULT_SAT = 60;

/** Основные цвета — одним нажатием; свой — ползунком оттенка. */
export const PRESETS: { id: string; label: string; hue: number | null }[] = [
	{ id: 'ink', label: 'Чернила', hue: null },
	{ id: 'blue', label: 'Синий', hue: 262 },
	{ id: 'violet', label: 'Фиолетовый', hue: 295 },
	{ id: 'pink', label: 'Розовый', hue: 350 },
	{ id: 'red', label: 'Красный', hue: 25 },
	{ id: 'orange', label: 'Оранжевый', hue: 52 },
	{ id: 'amber', label: 'Янтарный', hue: 80 },
	{ id: 'green', label: 'Зелёный', hue: 150 },
	{ id: 'teal', label: 'Бирюзовый', hue: 195 }
];

const KEY = 'gb-accent';
const CSS_KEY = 'gb-accent-css';
/** Цветовые темы до 0.4.10 — переводим в оттенок. */
const OLD_PALETTE: Record<string, number | null> = {
	graphite: null,
	ocean: 262,
	forest: 150,
	sunset: 35,
	grape: 295
};

// ---------- OKLCH → sRGB (Björn Ottosson), с уменьшением насыщенности до видимого цвета ----------

function toLinear(l: number, c: number, h: number): [number, number, number] {
	const a = c * Math.cos((h * Math.PI) / 180);
	const b = c * Math.sin((h * Math.PI) / 180);
	const l_ = (l + 0.3963377774 * a + 0.2158037573 * b) ** 3;
	const m_ = (l - 0.1055613458 * a - 0.0638541728 * b) ** 3;
	const s_ = (l - 0.0894841775 * a - 1.291485548 * b) ** 3;
	return [
		4.0767416621 * l_ - 3.3077115913 * m_ + 0.2309699292 * s_,
		-1.2684380046 * l_ + 2.6097574011 * m_ - 0.3413193965 * s_,
		-0.0041960863 * l_ - 0.7034186147 * m_ + 1.707614701 * s_
	];
}

const inGamut = (rgb: number[]) => rgb.every((v) => v >= -0.0005 && v <= 1.0005);

function gamma(v: number): number {
	const x = Math.min(1, Math.max(0, v));
	return x <= 0.0031308 ? 12.92 * x : 1.055 * x ** (1 / 2.4) - 0.055;
}

/** Цвет OKLCH в #rrggbb; слишком яркий для экрана — с меньшей насыщенностью того же оттенка. */
export function oklch(l: number, c: number, h: number): string {
	let lo = 0;
	let hi = c;
	let rgb = toLinear(l, c, h);
	if (!inGamut(rgb)) {
		for (let i = 0; i < 18; i++) {
			const mid = (lo + hi) / 2;
			if (inGamut(toLinear(l, mid, h))) lo = mid;
			else hi = mid;
		}
		rgb = toLinear(l, lo, h);
	}
	return (
		'#' +
		rgb
			.map((v) =>
				Math.round(gamma(v) * 255)
					.toString(16)
					.padStart(2, '0')
			)
			.join('')
	);
}

/**
 * Переменные темы для оттенка и насыщенности: --pal-* — светлый режим, --pald-* — тёмный
 * (app.css берёт их вместо «Классики»). Насыщенность 0 — серый, 100 — сочный цвет и заметно
 * цветной фон.
 */
export function accentVars({ hue, sat }: Accent): Record<string, string> {
	if (hue === null) return {};
	const h = hue;
	const k = Math.min(100, Math.max(0, sat)) / 100;
	const acc = 0.035 + 0.19 * k;
	const tint = 0.045 * k;
	return {
		'--pal-bg': oklch(0.962, tint * 0.55, h),
		'--pal-surface': oklch(0.997, tint * 0.08, h),
		'--pal-surface-2': oklch(0.965, tint * 0.4, h),
		'--pal-surface-3': oklch(0.925, tint * 0.55, h),
		'--pal-border': oklch(0.91, tint * 0.45, h),
		'--pal-accent': oklch(0.54, acc, h),
		'--pal-accent-hover': oklch(0.48, acc, h),
		'--pal-accent-soft': oklch(0.93, 0.02 + 0.05 * k, h),
		'--pal-accent-text': '#ffffff',
		'--pal-inverse': oklch(0.27, 0.02 + 0.08 * k, h),
		'--pal-inverse-2': oklch(0.33, 0.02 + 0.08 * k, h),
		'--pald-bg': oklch(0.14, tint * 0.75, h),
		'--pald-surface': oklch(0.2, tint * 0.8, h),
		'--pald-surface-2': oklch(0.25, tint * 0.85, h),
		'--pald-surface-3': oklch(0.31, tint * 0.9, h),
		'--pald-border': oklch(0.27, tint * 0.8, h),
		'--pald-accent': oklch(0.8, 0.03 + 0.15 * k, h),
		'--pald-accent-hover': oklch(0.86, 0.03 + 0.12 * k, h),
		'--pald-accent-soft': oklch(0.3, 0.02 + 0.07 * k, h),
		'--pald-accent-text': oklch(0.2, 0.02 + 0.06 * k, h),
		// Пятна света для «Стекла» и «Сияния»: основной цвет и два соседних оттенка.
		'--mesh-1': oklch(0.68, 0.06 + 0.16 * k, h),
		'--mesh-2': oklch(0.74, 0.05 + 0.13 * k, (h + 48) % 360),
		'--mesh-3': oklch(0.72, 0.05 + 0.14 * k, (h + 312) % 360),
		'--accent-h': String(h)
	};
}

export function cssText(a: Accent): string {
	return Object.entries(accentVars(a))
		.map(([k, v]) => `${k}:${v}`)
		.join(';');
}

function valid(v: unknown): v is Accent {
	const a = v as Accent;
	return (
		!!a &&
		typeof a === 'object' &&
		(a.hue === null || (typeof a.hue === 'number' && a.hue >= 0 && a.hue <= 360)) &&
		typeof a.sat === 'number' &&
		a.sat >= 0 &&
		a.sat <= 100
	);
}

export function currentAccent(): Accent {
	try {
		const v = JSON.parse(localStorage.getItem(KEY) ?? 'null');
		if (valid(v)) return v;
		const old = localStorage.getItem('gb-palette');
		if (old && old in OLD_PALETTE) return { hue: OLD_PALETTE[old], sat: DEFAULT_SAT };
	} catch {
		/* приватный режим или мусор в хранилище */
	}
	return { hue: null, sat: DEFAULT_SAT };
}

/** Ставит цвет сразу (переменные на <html>) и запоминает на этом устройстве. */
export function setAccent(a: Accent, smooth = true) {
	const root = document.documentElement;
	if (smooth) root.classList.add('theme-switching');
	const old = Array.from({ length: root.style.length }, (_, i) => root.style.item(i));
	for (const name of old) if (/^--(pal|mesh|accent-h)/.test(name)) root.style.removeProperty(name);
	for (const [k, v] of Object.entries(accentVars(a))) root.style.setProperty(k, v);
	if (a.hue === null) root.removeAttribute('data-accent');
	else root.setAttribute('data-accent', '');
	try {
		localStorage.removeItem('gb-palette');
		if (a.hue === null && a.sat === DEFAULT_SAT) {
			localStorage.removeItem(KEY);
			localStorage.removeItem(CSS_KEY);
		} else {
			localStorage.setItem(KEY, JSON.stringify(a));
			localStorage.setItem(CSS_KEY, cssText(a));
		}
	} catch {
		/* приватный режим — просто не запоминаем */
	}
	if (smooth) setTimeout(() => root.classList.remove('theme-switching'), 300);
}

/** Тема из прежней версии (gb-palette): один раз переводим в цвет — без мигания в следующий раз. */
export function migrateAccent() {
	try {
		if (!localStorage.getItem('gb-palette')) return;
		setAccent(currentAccent(), false);
	} catch {
		/* не вышло — останется «Классика» */
	}
}
