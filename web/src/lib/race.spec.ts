import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { within } from './race';

describe('within', () => {
	beforeEach(() => vi.useFakeTimers());
	afterEach(() => vi.useRealTimers());

	it('отдаёт ответ, пришедший вовремя', async () => {
		const r = within(Promise.resolve(42), 1000);
		await expect(r).resolves.toEqual({ ok: true, value: 42 });
	});

	it('отдаёт ошибку, случившуюся вовремя', async () => {
		const e = new Error('нет сети');
		await expect(within(Promise.reject(e), 1000)).resolves.toEqual({ ok: false, error: e });
	});

	it('не ждёт дольше срока, а сам ответ приходит позже', async () => {
		let answer!: (v: string) => void;
		const slow = new Promise<string>((res) => (answer = res));
		const r = within(slow, 1500);
		await vi.advanceTimersByTimeAsync(1500);
		await expect(r).resolves.toBeNull();
		answer('свежее');
		await expect(slow).resolves.toBe('свежее');
	});
});
