import { defineConfig } from 'vitest/config';
import type { Plugin } from 'vite';
import adapter from '@sveltejs/adapter-static';
import { sveltekit } from '@sveltejs/kit/vite';

/**
 * Код, без которого не открыть ни одну страницу приложения (корневой макет и макет (app) со всем,
 * что они импортируют), — одним файлом. Иначе сборщик режет его на десятки кусочков по 200 байт
 * (они нужны разным наборам страниц), и телефон через туннель — HTTP/1.1, по 6 запросов за раз —
 * грузит первый экран в полтора десятка очередей. Каждой странице этот код нужен целиком всё равно.
 */
const shell = new Set<string>();
const shellChunk: Plugin = {
	name: 'groupbase-shell-chunk',
	apply: 'build',
	buildEnd() {
		shell.clear();
		const layouts = [...this.getModuleIds()].filter((id) =>
			/[\\/]src[\\/]routes[\\/](\(app\)[\\/])?\+layout\.svelte$/.test(id)
		);
		// Сами макеты остаются в своих файлах: у макета (app) есть ленивые части (просмотр файлов,
		// палитра), им не место в коде, который грузит и страница входа.
		const stack = layouts.flatMap((id) => this.getModuleInfo(id)?.importedIds ?? []);
		while (stack.length) {
			const id = stack.pop()!;
			if (shell.has(id) || layouts.includes(id)) continue;
			shell.add(id);
			stack.push(...(this.getModuleInfo(id)?.importedIds ?? []));
		}
	}
};

const backend = process.env.GROUPBASE_DEV_BACKEND ?? 'http://127.0.0.1:8080';

export default defineConfig({
	plugins: [
		shellChunk,
		sveltekit({
			compilerOptions: {
				runes: ({ filename }) =>
					filename.split(/[/\\]/).includes('node_modules') ? undefined : true
			},
			// SPA: всё за авторизацией, поэтому без пререндеринга; бэкенд отдаёт index.html на любой путь.
			adapter: adapter({ fallback: 'index.html', precompress: true, strict: true }),
			output: { bundleStrategy: 'split' },
			// Регистрирует сам интерфейс (lib/pwa.svelte.ts) — и не в окне приложения хоста.
			serviceWorker: { register: false },
			version: { pollInterval: 0 }
		})
	],
	server: {
		proxy: { '/api': { target: backend, changeOrigin: false } }
	},
	build: {
		target: 'es2022',
		reportCompressedSize: true,
		rolldownOptions: {
			output: {
				codeSplitting: {
					// shell — см. выше; common — наше, что нужно шести страницам и больше (карточки,
					// строки заданий): одним файлом вместо десятка. Порог 6 — при меньшем самая
					// тяжёлая страница выходит за 100 КБ (scripts/bundle-size.mjs).
					groups: [
						{ name: 'shell', test: (id) => shell.has(id), priority: 2 },
						{ name: 'common', test: /[\\/]src[\\/]lib[\\/]/, minShareCount: 6, priority: 1 }
					]
				}
			}
		}
	},
	test: {
		expect: { requireAssertions: true },
		projects: [
			{
				extends: './vite.config.ts',
				test: {
					name: 'unit',
					environment: 'node',
					include: ['src/**/*.{test,spec}.{js,ts}']
				}
			}
		]
	}
});
