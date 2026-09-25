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

/**
 * Понятная причина, почему не вышло. При входе «отменено» означает и «ключа нет»: браузер не
 * говорит сайту, есть ли на устройстве ключ, — отказ выглядит одинаково.
 */
export function passkeyError(
	e: unknown,
	when: 'add' | 'login' = 'add',
	securityKey = false
): string {
	if (e instanceof DOMException) {
		if (e.name === 'NotAllowedError' || e.name === 'AbortError')
			return when === 'login'
				? 'Вход отменён или на этом устройстве нет ключа для этого сайта. Попробуйте ещё раз или войдите по паролю'
				: 'Отменено — можно попробовать ещё раз';
		if (e.name === 'InvalidStateError') return 'Этот ключ уже добавлен';
		if (e.name === 'NotSupportedError')
			return securityKey
				? 'Этот ключ не умеет хранить вход без логина — нужен ключ FIDO2 с PIN-кодом (например, YubiKey 5)'
				: 'Это устройство не умеет входить по ключу';
		if (e.name === 'SecurityError')
			return 'Ключи работают только по адресу сайта с https — не по IP-адресу';
	}
	return e instanceof Error ? e.message : 'Не получилось';
}

interface CreateOptions {
	/** «security-key» — браузер сразу просит приложить ключ безопасности (USB, NFC). */
	hints?: string[];
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
	/** Домен сайта, для которого создан ключ: на другом адресе им не войти. */
	site: string;
}

/**
 * @param securityKey ключ безопасности (USB, NFC): браузер сразу попросит его приложить, а не
 *   предложит сохранить ключ в телефоне
 */
export async function addPasskey(
	password: string,
	name: string,
	securityKey = false
): Promise<PasskeyInfo> {
	const o = await post<CreateOptions>('/api/me/passkeys/options', { password, securityKey });
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

/** Браузер умеет подсказывать ключ прямо в поле логина (как сохранённый пароль). */
export async function passkeyAutofill(): Promise<boolean> {
	try {
		return (await PublicKeyCredential.isConditionalMediationAvailable?.()) === true;
	} catch {
		return false;
	}
}

/**
 * Вход ключом. autofill — ждать, пока человек выберет ключ в подсказке у поля логина
 * (mediation: conditional); такой запрос отменяют через signal, чтобы нажать «Войти по ключу».
 */
export async function loginWithPasskey(
	opts: { autofill?: boolean; signal?: AbortSignal } = {}
): Promise<void> {
	const o = await post<GetOptions>('/api/auth/passkey/options', {}, { anonymous: true });
	const cred = (await navigator.credentials.get({
		publicKey: { ...o, challenge: fromB64u(o.challenge), allowCredentials: [] },
		...(opts.autofill ? { mediation: 'conditional' as CredentialMediationRequirement } : {}),
		signal: opts.signal
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
