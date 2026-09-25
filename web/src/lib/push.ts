import { del, post } from '$lib/api';

/** Подписка этого браузера на push-уведомления. */

export function pushSupported(): boolean {
	return (
		typeof window !== 'undefined' &&
		'serviceWorker' in navigator &&
		'PushManager' in window &&
		'Notification' in window
	);
}

export function isIos(): boolean {
	return /iPhone|iPad|iPod/.test(navigator.userAgent);
}

/** Открыто как установленное приложение (с экрана «Домой»). */
export function isStandalone(): boolean {
	return (
		matchMedia('(display-mode: standalone)').matches ||
		(navigator as Navigator & { standalone?: boolean }).standalone === true
	);
}

/** На iPhone push работает только в установленном приложении. */
export function needsInstallForPush(): boolean {
	return isIos() && !isStandalone();
}

/** «Chrome, Android» — чтобы отличать устройства в списке. */
export function deviceLabel(): string {
	const ua = navigator.userAgent;
	const browser = /YaBrowser/.test(ua)
		? 'Яндекс Браузер'
		: /Edg\//.test(ua)
			? 'Edge'
			: /Firefox\//.test(ua)
				? 'Firefox'
				: /Chrome\//.test(ua)
					? 'Chrome'
					: /Safari\//.test(ua)
						? 'Safari'
						: 'Браузер';
	const os = /Android/.test(ua)
		? 'Android'
		: /iPhone|iPad|iPod/.test(ua)
			? 'iOS'
			: /Windows/.test(ua)
				? 'Windows'
				: /Mac OS X/.test(ua)
					? 'macOS'
					: /Linux/.test(ua)
						? 'Linux'
						: '';
	return os ? `${browser}, ${os}` : browser;
}

function keyBytes(b64: string): Uint8Array<ArrayBuffer> {
	const s = atob(
		b64.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (b64.length % 4)) % 4)
	);
	const out = new Uint8Array(new ArrayBuffer(s.length));
	for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
	return out;
}

export async function currentSubscription(): Promise<PushSubscription | null> {
	if (!pushSupported()) return null;
	const reg = await navigator.serviceWorker.getRegistration();
	return (await reg?.pushManager.getSubscription()) ?? null;
}

/** Подписка сделана с другим ключом сервера (его сменили) — такая не заработает. */
function staleKey(sub: PushSubscription, publicKey: string): boolean {
	const old = sub.options.applicationServerKey;
	if (!old) return false;
	const key = keyBytes(publicKey);
	return btoa(String.fromCharCode(...new Uint8Array(old))) !== btoa(String.fromCharCode(...key));
}

async function send(sub: PushSubscription): Promise<void> {
	const json = sub.toJSON();
	await post('/api/push/subscriptions', {
		endpoint: sub.endpoint,
		p256dh: json.keys?.p256dh,
		auth: json.keys?.auth,
		device: deviceLabel()
	});
}

export async function enablePush(publicKey: string): Promise<void> {
	if (!pushSupported()) throw new Error('Этот браузер не умеет показывать уведомления');
	const permission = await Notification.requestPermission();
	if (permission !== 'granted')
		throw new Error('Уведомления запрещены. Разрешите их в настройках браузера для этого сайта');
	const reg = await navigator.serviceWorker.ready;
	let sub = await reg.pushManager.getSubscription();
	// Подписка со старым ключом сервера не заработает — пересоздаём.
	if (sub && staleKey(sub, publicKey)) {
		await sub.unsubscribe();
		sub = null;
	}
	sub ??= await reg.pushManager.subscribe({
		userVisibleOnly: true,
		applicationServerKey: keyBytes(publicKey)
	});
	await send(sub);
}

/**
 * Напоминает серверу подписку этого устройства — без вопросов о разрешении. Сервер удаляет
 * подписку после нескольких неудачных отправок, а телефон об этом не знает и считает, что всё
 * включено. 'stale' — подписка со старым ключом сервера: включить заново можно только нажатием.
 */
export async function resendSubscription(publicKey?: string): Promise<'ok' | 'none' | 'stale'> {
	const sub = await currentSubscription();
	if (!sub) return 'none';
	if (publicKey && staleKey(sub, publicKey)) return 'stale';
	await send(sub);
	return 'ok';
}

const RESENT = 'gb-push-resent';

/** При запуске, не чаще раза в 12 часов: сервер снова знает это устройство. */
export async function resendOnStart(): Promise<void> {
	if (!pushSupported() || Notification.permission !== 'granted') return;
	try {
		if (Date.now() - Number(localStorage.getItem(RESENT) ?? 0) < 12 * 3600_000) return;
	} catch {
		/* нет доступа к хранилищу — просто отправим */
	}
	try {
		if ((await resendSubscription()) !== 'ok') return;
		localStorage.setItem(RESENT, String(Date.now()));
	} catch {
		/* офлайн или сервер недоступен — в следующий раз */
	}
}

/** Итог «Проверить»: сколько устройств приняли и что ответила служба push, если не приняла. */
export interface PushReport {
	devices: number;
	delivered: number;
	status: number;
	reason: string;
}

/**
 * Понятное объяснение итога проверки. again — подписку надо включить заново (нажатием), её
 * больше нет или она сделана со старым ключом.
 */
export function explainPushTest(r: PushReport): { ok: boolean; text: string; again?: boolean } {
	if (r.delivered > 0)
		return { ok: true, text: 'Отправили — уведомление появится через несколько секунд' };
	const why = r.reason ? ` (${r.reason})` : '';
	if (r.devices === 0 || r.status === 404 || r.status === 410 || r.reason === 'VapidPkHashMismatch')
		return {
			ok: false,
			again: true,
			text: 'Подписка этого устройства устарела — нажмите «Включить уведомления» ещё раз'
		};
	if (r.status === -1)
		return {
			ok: false,
			text: 'Сервер не смог связаться со службой уведомлений. Проверьте интернет на компьютере, где работает сайт'
		};
	if (r.status === 401 || r.status === 403)
		return {
			ok: false,
			text: `Служба уведомлений не приняла подпись сервера${why}. Обновите groupbase на компьютере, где работает сайт`
		};
	if (r.status === 429)
		return { ok: false, text: 'Служба уведомлений просит подождать — попробуйте через минуту' };
	return {
		ok: false,
		text: `Служба уведомлений ответила ${r.status}${why} — попробуйте позже`
	};
}

export async function disablePush(): Promise<void> {
	const sub = await currentSubscription();
	if (!sub) return;
	await del('/api/push/subscriptions', { endpoint: sub.endpoint }).catch(() => {});
	await sub.unsubscribe();
}
