// Масштаб интерфейса (0.9.5): свой на каждом устройстве, не синхронизируется (localStorage
// gb-ui-scale). На компьютере – CSS zoom на <html>. На телефоне zoom ломал касания (половина экрана
// не нажималась, страница уезжала вбок) и подсветку тура, поэтому там масштабирует сам браузер:
// ширина страницы в meta viewport. Тот же расчёт – в скрипте app.html до отрисовки.

export const SCALES = [80, 90, 100, 110, 125, 150];
const KEY = 'gb-ui-scale';
const BASE =
	'minimum-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover, interactive-widget=resizes-content';

export function readScale(): number {
	try {
		const v = Number(localStorage.getItem(KEY));
		return SCALES.includes(v) ? v : 100;
	} catch {
		return 100;
	}
}

/** Сенсорный экран без мыши – телефон или планшет. */
export const touchScreen = () =>
	typeof matchMedia !== 'undefined' && matchMedia('(hover: none) and (pointer: coarse)').matches;

/** Содержимое meta viewport для масштаба v на телефоне. */
export function viewportFor(v: number, landscape: boolean, sw: number, sh: number): string {
	if (v === 100) return `width=device-width, initial-scale=1, ${BASE}`;
	const device = landscape ? Math.max(sw, sh) : Math.min(sw, sh);
	const k = v / 100;
	const base = BASE.replace(
		'minimum-scale=1, maximum-scale=1',
		`minimum-scale=${k}, maximum-scale=${k}`
	);
	return `width=${Math.round(device / k)}, initial-scale=${k}, ${base}`;
}

let listening = false;

export function applyScale(v: number) {
	const root = document.documentElement;
	if (!touchScreen()) {
		root.style.zoom = v === 100 ? '' : String(v / 100);
		return;
	}
	root.style.zoom = '';
	const set = () => {
		const meta = document.querySelector('meta[name="viewport"]');
		meta?.setAttribute(
			'content',
			viewportFor(
				readScale(),
				matchMedia('(orientation: landscape)').matches,
				screen.width,
				screen.height
			)
		);
	};
	set();
	if (!listening) {
		listening = true;
		addEventListener('orientationchange', () => setTimeout(set, 50));
	}
}

export function setScale(v: number) {
	try {
		if (v === 100) localStorage.removeItem(KEY);
		else localStorage.setItem(KEY, String(v));
	} catch {
		/* приватный режим – масштаб до перезагрузки */
	}
	applyScale(v);
}
