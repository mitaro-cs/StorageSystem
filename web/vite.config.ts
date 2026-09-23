import { defineConfig } from 'vitest/config';
import adapter from '@sveltejs/adapter-static';
import { sveltekit } from '@sveltejs/kit/vite';

const backend = process.env.GROUPBASE_DEV_BACKEND ?? 'http://127.0.0.1:8080';

export default defineConfig({
	plugins: [
		sveltekit({
			compilerOptions: {
				runes: ({ filename }) =>
					filename.split(/[/\\]/).includes('node_modules') ? undefined : true
			},
			// SPA: всё за авторизацией, поэтому без пререндеринга; бэкенд отдаёт index.html на любой путь.
			adapter: adapter({ fallback: 'index.html', precompress: true, strict: true }),
			output: { bundleStrategy: 'split' },
			version: { pollInterval: 0 }
		})
	],
	server: {
		proxy: { '/api': { target: backend, changeOrigin: false } }
	},
	build: { target: 'es2022', reportCompressedSize: true },
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
