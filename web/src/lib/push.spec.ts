import { describe, expect, it } from 'vitest';
import { explainPushTest } from './push';

const report = (r: Partial<Parameters<typeof explainPushTest>[0]>) => ({
	devices: 1,
	delivered: 0,
	status: 0,
	reason: '',
	...r
});

describe('explainPushTest — что сказать после «Проверить»', () => {
	it('доставлено', () => {
		expect(explainPushTest(report({ delivered: 1 }))).toMatchObject({ ok: true });
	});

	it('подписки больше нет или она со старым ключом — включить заново', () => {
		for (const r of [
			report({ devices: 0 }),
			report({ status: 410 }),
			report({ status: 404 }),
			report({ status: 403, reason: 'VapidPkHashMismatch' })
		])
			expect(explainPushTest(r)).toMatchObject({ ok: false, again: true });
	});

	it('служба отвергла подпись сервера — дело в сервере, не в телефоне', () => {
		const r = explainPushTest(report({ status: 403, reason: 'BadJwtToken' }));
		expect(r.again).toBeUndefined();
		expect(r.text).toContain('BadJwtToken');
		expect(r.text).toContain('Обновите groupbase');
	});

	it('нет связи со службой и прочие ответы', () => {
		expect(explainPushTest(report({ status: -1 })).text).toContain('интернет');
		expect(explainPushTest(report({ status: 429 })).text).toContain('подождать');
		expect(explainPushTest(report({ status: 500, reason: 'x' })).text).toContain('500 (x)');
	});
});
