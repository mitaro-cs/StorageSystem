import { describe, expect, it } from 'vitest';
import { b64u, deviceName, fromB64u, passkeyError, passkeysSupported } from './passkey';

describe('ключи входа', () => {
	it('base64url туда и обратно, без «=», «+», «/»', () => {
		const bytes = new Uint8Array([251, 255, 1, 0, 62, 63]);
		const s = b64u(bytes);
		expect(s).not.toMatch(/[=+/]/);
		expect([...fromB64u(s)]).toEqual([...bytes]);
		expect([...fromB64u('AQ')]).toEqual([1]);
	});

	it('работают только на защищённом адресе с именем', () => {
		const w = (hostname: string, secure = true) => ({
			isSecureContext: secure,
			PublicKeyCredential: class {},
			location: { hostname }
		});
		expect(passkeysSupported(w('bik2401.cloudpub.ru'))).toBe(true);
		expect(passkeysSupported(w('localhost'))).toBe(true);
		// Окно хоста (127.0.0.1) и адрес в локальной сети — по IP ключи не работают.
		expect(passkeysSupported(w('127.0.0.1'))).toBe(false);
		expect(passkeysSupported(w('192.168.1.5'))).toBe(false);
		expect(passkeysSupported(w('site.ru', false))).toBe(false);
		expect(passkeysSupported({ isSecureContext: true, location: { hostname: 'site.ru' } })).toBe(
			false
		);
	});

	it('имя ключа по устройству', () => {
		expect(deviceName('Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)')).toBe('iPhone');
		expect(deviceName('Mozilla/5.0 (Linux; Android 15; Pixel 9)')).toBe('Android');
		expect(deviceName('Mozilla/5.0 (Macintosh; Intel Mac OS X 15_0)')).toBe('Mac');
		expect(deviceName('Mozilla/5.0 (Windows NT 10.0; Win64; x64)')).toBe('Windows');
		expect(deviceName('curl')).toBe('Ключ');
	});
});

describe('passkeyError', () => {
	const dom = (name: string) => new DOMException('x', name);

	it('при входе «отменено» значит и «на устройстве нет ключа»', () => {
		expect(passkeyError(dom('NotAllowedError'), 'login')).toMatch(/нет ключа/);
		expect(passkeyError(dom('NotAllowedError'))).toMatch(/^Отменено/);
	});

	it('ключ безопасности без хранения входа — подсказываем, какой нужен', () => {
		expect(passkeyError(dom('NotSupportedError'), 'add', true)).toMatch(/FIDO2/);
		expect(passkeyError(dom('NotSupportedError'))).toMatch(/не умеет/);
	});

	it('не по адресу сайта и прочее', () => {
		expect(passkeyError(dom('SecurityError'))).toMatch(/https/);
		expect(passkeyError(dom('InvalidStateError'))).toBe('Этот ключ уже добавлен');
		expect(passkeyError(new Error('Сервер недоступен'))).toBe('Сервер недоступен');
	});
});
