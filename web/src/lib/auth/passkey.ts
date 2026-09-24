import { post } from '$lib/api';

/**
 * Вход по отпечатку или лицу (WebAuthn, passkeys). Сервер отдаёт параметры в JSON с base64url, а
 * браузеру нужны байты. Открытый ключ берём у браузера (getPublicKey) — серверу не нужен CBOR.
 */

export function b64u(data: ArrayBuffer | Uint8Array): string {
	const bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
	let s = '';
	for (const b of bytes) s += String.fromCharCode(b);
	return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function fromB64u(s: string): Uint8Array<ArrayBuffer> {
	const bin = atob(s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4));
	const out = new Uint8Array(new ArrayBuffer(bin.length));
	for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
	return out;
}

/** Ключи работают только на защищённом адресе с именем сайта: не по IP и не в окне хоста. */
export function passkeysSupported(
	w: {
		isSecureContext?: boolean;
		PublicKeyCredential?: unknown;
		location?: { hostname: string };
	} = globalThis
): boolean {
	const host = w.location?.hostname ?? '';
	return (
		!!w.isSecureContext &&
		typeof w.PublicKeyCredential !== 'undefined' &&
		host !== '' &&
		!/^[\d.]+$/.test(host) &&
		!host.includes(':') &&
		!host.startsWith('[')
	);
}

/** Имя ключа по устройству: «iPhone», «Mac», «Android»… */
export function deviceName(ua: string = globalThis.navigator?.userAgent ?? ''): string {
	if (/iPhone/.test(ua)) return 'iPhone';
	if (/iPad/.test(ua)) return 'iPad';
	if (/Android/.test(ua)) return 'Android';
	if (/Macintosh|Mac OS X/.test(ua)) return 'Mac';
	if (/Windows/.test(ua)) return 'Windows';
	if (/Linux/.test(ua)) return 'Linux';
	return 'Ключ';
}

/** Понятная причина, почему не вышло: отмена, уже есть, не поддерживается. */
export function passkeyError(e: unknown): string {
	if (e instanceof DOMException) {
		if (e.name === 'NotAllowedError' || e.name === 'AbortError')
			return 'Отменено — можно попробовать ещё раз';
		if (e.name === 'InvalidStateError') return 'Этот ключ уже добавлен';
		if (e.name === 'NotSupportedError') return 'Это устройство не умеет входить по ключу';
		if (e.name === 'SecurityError') return 'Вход по ключу работает только на адресе сайта';
	}
	return e instanceof Error ? e.message : 'Не получилось';
}

interface CreateOptions {
	challenge: string;
	rp: { id: string; name: string };
	user: { id: string; name: string; displayName: string };
	pubKeyCredParams: { type: 'public-key'; alg: number }[];
	timeout: number;
	attestation: AttestationConveyancePreference;
	authenticatorSelection: AuthenticatorSelectionCriteria;
	excludeCredentials: { type: 'public-key'; id: string }[];
}

interface GetOptions {
	challenge: string;
	rpId: string;
	timeout: number;
	userVerification: UserVerificationRequirement;
}

export interface PasskeyInfo {
	id: number;
	name: string;
	createdAt: number;
	lastUsedAt: number | null;
}

export async function addPasskey(password: string, name: string): Promise<PasskeyInfo> {
	const o = await post<CreateOptions>('/api/me/passkeys/options', { password });
	const cred = (await navigator.credentials.create({
		publicKey: {
			...o,
			challenge: fromB64u(o.challenge),
			user: { ...o.user, id: fromB64u(o.user.id) },
			excludeCredentials: o.excludeCredentials.map((c) => ({ ...c, id: fromB64u(c.id) }))
		}
	})) as PublicKeyCredential | null;
	if (!cred) throw new DOMException('cancelled', 'NotAllowedError');
	const r = cred.response as AuthenticatorAttestationResponse;
	const spki = r.getPublicKey?.();
	const data = r.getAuthenticatorData?.();
	if (!spki || !data) throw new Error('Обновите браузер: этот не умеет ключи входа');
	return post<PasskeyInfo>('/api/me/passkeys', {
		credentialId: b64u(cred.rawId),
		clientDataJSON: b64u(r.clientDataJSON),
		authenticatorData: b64u(data),
		publicKey: b64u(spki),
		algorithm: r.getPublicKeyAlgorithm(),
		name
	});
}

export async function loginWithPasskey(): Promise<void> {
	const o = await post<GetOptions>('/api/auth/passkey/options', {}, { anonymous: true });
	const cred = (await navigator.credentials.get({
		publicKey: { ...o, challenge: fromB64u(o.challenge), allowCredentials: [] }
	})) as PublicKeyCredential | null;
	if (!cred) throw new DOMException('cancelled', 'NotAllowedError');
	const r = cred.response as AuthenticatorAssertionResponse;
	await post(
		'/api/auth/passkey',
		{
			credentialId: b64u(cred.rawId),
			clientDataJSON: b64u(r.clientDataJSON),
			authenticatorData: b64u(r.authenticatorData),
			signature: b64u(r.signature),
			userHandle: r.userHandle ? b64u(r.userHandle) : null
		},
		{ anonymous: true }
	);
}
