import { request } from '$lib/api';

// Скорость сайта глазами группы (1.0.2): браузер сам меряет, за сколько приходят ответы API и
// открывается страница, и раз в три минуты отдаёт замеры хосту – «Управление → Мониторинг». Только
// числа, вид устройства и сети; окно на компьютере хоста не меряется (оно в той же машине).

type Device = 'phone' | 'tablet' | 'computer';
type Net = 'wifi' | 'cellular' | 'ethernet' | 'other' | 'unknown';

const PERIOD = 3 * 60_000;
const MAX = 100;

export function device(coarse: boolean, shortSide: number): Device {
	if (!coarse) return 'computer';
	return shortSide < 600 ? 'phone' : 'tablet';
}

/** Тип сети знает только Chrome на Android; остальные – «не известна». */
export function net(type: string | undefined): Net {
	if (type === 'wifi' || type === 'cellular' || type === 'ethernet') return type;
	return type && type !== 'unknown' && type !== 'none' ? 'other' : 'unknown';
}

/** Локальная сеть – адрес вида 192.168.…, 10.…, 172.16–31.…, localhost или *.local. */
export function via(host: string): 'local' | 'tunnel' {
	return /^(localhost|127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|\[?::1\]?$)|\.local$/.test(
		host
	)
		? 'local'
		: 'tunnel';
}

/** Запросы API, которые стоит мерить: без потока событий (он открыт минутами) и самих замеров. */
export const measured = (url: string) =>
	url.includes('/api/') && !url.includes('/api/live') && !url.includes('/api/monitor/timings');

export function startRum(): () => void {
	if (typeof PerformanceObserver === 'undefined') return () => {};
	let samples: number[] = [];
	let load: number | undefined;
	const nav = performance.getEntriesByType('navigation')[0] as
		PerformanceNavigationTiming | undefined;
	if (nav && nav.domContentLoadedEventEnd > 0) load = Math.round(nav.domContentLoadedEventEnd);
	const obs = new PerformanceObserver((list) => {
		for (const e of list.getEntries()) {
			if (samples.length < MAX && measured(e.name) && e.duration > 0)
				samples.push(Math.round(e.duration));
		}
	});
	try {
		obs.observe({ type: 'resource', buffered: true });
	} catch {
		return () => {};
	}

	async function send() {
		if (document.visibilityState !== 'visible' && !samples.length) return;
		if (!samples.length && load === undefined) return;
		const conn = (navigator as Navigator & { connection?: { type?: string } }).connection;
		const body = {
			device: device(
				matchMedia('(pointer: coarse)').matches,
				Math.min(screen.width, screen.height)
			),
			net: net(conn?.type),
			via: via(location.hostname),
			api: samples,
			load
		};
		samples = [];
		load = undefined;
		try {
			await request('/api/monitor/timings', { method: 'POST', body, quiet401: true });
		} catch {
			// Замер – не главное: не ушёл – следующий через три минуты.
		}
	}

	const first = setTimeout(send, 20_000);
	const timer = setInterval(send, PERIOD);
	return () => {
		clearTimeout(first);
		clearInterval(timer);
		obs.disconnect();
	};
}
