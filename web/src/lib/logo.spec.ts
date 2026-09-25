import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

// Логотип нарисован один раз — static/logo.svg; заставки сайта и приложения хоста держат его копию
// (им нужен сразу, до загрузки файлов). Копии не должны разойтись с оригиналом.
const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');
const pick = (text: string, re: RegExp) => text.match(re)?.[1];

describe('логотип', () => {
	const logo = read('../../static/logo.svg');
	const figure = pick(logo, /id="figure"[^>]* d="([^"]+)"/);
	const globe = pick(logo, /<circle id="globe" (cx="[^"]+" cy="[^"]+" r="[^"]+")/);

	it('в logo.svg есть глобус и фигура', () => {
		expect(figure?.length).toBeGreaterThan(1000);
		expect(globe).toBeTruthy();
	});

	for (const page of ['../app.html', '../../../desktop/ui/index.html']) {
		it(`${page.split('/').slice(-2).join('/')} — тот же логотип`, () => {
			const html = read(page);
			expect(pick(html, /class="fig"\s+d="([^"]+)"/)).toBe(figure);
			expect(pick(html, /<circle class="ball" (cx="[^"]+" cy="[^"]+" r="[^"]+")/)).toBe(globe);
		});
	}
});
