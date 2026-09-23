import { describe, expect, it } from 'vitest';
import { passphrase, wordCount } from './passphrase';

describe('passphrase', () => {
	it('четыре русских слова и число, длиннее 10 символов', () => {
		for (let i = 0; i < 50; i++) {
			const p = passphrase();
			expect(p).toMatch(/^[а-я]+-[а-я]+-[а-я]+-[а-я]+-\d{2}$/);
			expect(p.length).toBeGreaterThanOrEqual(10);
		}
		expect(wordCount()).toBeGreaterThanOrEqual(150);
	});
});
