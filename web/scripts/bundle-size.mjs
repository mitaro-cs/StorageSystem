// Считает суммарный gzip-размер JS, который загружается на стартовой странице (index.html + импорты).
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { gzipSync } from 'node:zlib';

const limit = Number(process.argv[2] ?? 102400);
const dir = 'build/_app/immutable';
let total = 0;
function walk(d) {
	for (const f of readdirSync(d)) {
		const p = join(d, f);
		if (statSync(p).isDirectory()) walk(p);
		else if (p.endsWith('.js')) total += gzipSync(readFileSync(p)).length;
	}
}
walk(dir);
const kb = (total / 1024).toFixed(1);
console.log(`JS gzip: ${kb} КБ (лимит ${(limit / 1024).toFixed(0)} КБ)`);
if (total > limit) {
	console.error('Бандл больше лимита');
	process.exit(1);
}
