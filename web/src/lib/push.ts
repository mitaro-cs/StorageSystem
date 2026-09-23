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

export async function enablePush(publicKey: string): Promise<void> {
	if (!pushSupported()) throw new Error('Этот браузер не умеет показывать уведомления');
	const permission = await Notification.requestPermission();
	if (permission !== 'granted')
		throw new Error('Уведомления запрещены. Разрешите их в настройках браузера для этого сайта');
	const reg = await navigator.serviceWorker.ready;
	let sub = await reg.pushManager.getSubscription();
	const key = keyBytes(publicKey);
	// Подписка со старым ключом сервера не заработает — пересоздаём.
	const old = sub?.options.applicationServerKey;
	if (
		sub &&
		old &&
		btoa(String.fromCharCode(...new Uint8Array(old))) !== btoa(String.fromCharCode(...key))
	) {
		await sub.unsubscribe();
		sub = null;
	}
	sub ??= await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key });
	const json = sub.toJSON();
	await post('/api/push/subscriptions', {
		endpoint: sub.endpoint,
		p256dh: json.keys?.p256dh,
		auth: json.keys?.auth,
		device: deviceLabel()
	});
}

export async function disablePush(): Promise<void> {
	const sub = await currentSubscription();
	if (!sub) return;
	await del('/api/push/subscriptions', { endpoint: sub.endpoint }).catch(() => {});
	await sub.unsubscribe();
}
