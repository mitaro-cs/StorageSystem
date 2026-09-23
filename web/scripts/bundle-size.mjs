// Проверяет, сколько JS (gzip) загружает самая тяжёлая страница: точка входа, все layout-узлы
// и сама страница со всеми статическими импортами. Код других страниц грузится только при переходе.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { gzipSync } from 'node:zlib';

const limit = Number(process.argv[2] ?? 102400);
const manifest = JSON.parse(readFileSync('.svelte-kit/output/client/.vite/manifest.json', 'utf8'));
const gz = new Map();
const size = (file) => {
	if (!gz.has(file)) gz.set(file, gzipSync(readFileSync(join('build', file))).length);
	return gz.get(file);
};

function closure(keys, seen = new Set()) {
	for (const k of keys) {
		if (seen.has(k) || !manifest[k]) continue;
		seen.add(k);
		closure(manifest[k].imports ?? [], seen);
	}
	return seen;
}

const nodeSource = (k) =>
	readFileSync(k, 'utf8').match(/routes\/(.*?\+(?:page|layout|error)\.svelte)/)?.[1] ?? k;
const entries = Object.keys(manifest).filter((k) => manifest[k].isEntry && !k.includes('/nodes/'));
const nodes = Object.keys(manifest).filter((k) => k.includes('/nodes/'));
const layouts = nodes.filter((k) => nodeSource(k).includes('+layout'));
const pages = nodes.filter((k) => nodeSource(k).includes('+page'));

let worst = { route: '', bytes: 0 };
for (const p of pages) {
	const files = [...closure([...entries, ...layouts, p])].map((k) => manifest[k].file);
	const bytes = files.filter((f) => f.endsWith('.js')).reduce((s, f) => s + size(f), 0);
	if (bytes > worst.bytes) worst = { route: nodeSource(p), bytes };
}
const total = [...new Set(Object.values(manifest).map((v) => v.file))]
	.filter((f) => f.endsWith('.js'))
	.reduce((s, f) => s + size(f), 0);

const kb = (b) => (b / 1024).toFixed(1);
console.log(
	`Самая тяжёлая страница: ${worst.route} — ${kb(worst.bytes)} КБ gzip (лимит ${kb(limit)} КБ)`
);
console.log(`Весь JS приложения: ${kb(total)} КБ gzip (грузится по страницам)`);
if (worst.bytes > limit) {
	console.error('Страница превышает лимит');
	process.exit(1);
}
