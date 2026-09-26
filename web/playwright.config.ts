import { defineConfig, devices } from '@playwright/test';

// E2E идут против собранного jar (make build) на чистой временной БД.
const port = Number(process.env.E2E_PORT ?? 18765);

export default defineConfig({
	testDir: 'e2e',
	testMatch: '**/*.e2e.ts',
	fullyParallel: false,
	workers: 1,
	retries: process.env.CI ? 1 : 0,
	reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
	use: {
		baseURL: `http://127.0.0.1:${port}`,
		locale: 'ru-RU',
		timezoneId: 'Europe/Moscow',
		trace: 'retain-on-failure'
	},
	projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
	webServer: {
		command: 'rm -rf .e2e-data && java -jar ../target/groupbase.jar serve',
		url: `http://127.0.0.1:${port}/api/health`,
		reuseExistingServer: false,
		timeout: 60_000,
		env: {
			GROUPBASE_DATA_DIR: '.e2e-data',
			GROUPBASE_HTTP_PORT: String(port),
			GROUPBASE_HTTP_INSECURE: 'true',
			GROUPBASE_AUTH_REQUIRE_STAFF_TOTP: 'false',
			GROUPBASE_SETUP_CODE: 'e2e-setup-code',
			// Без запросов к GitHub: «Проверить обновления» отвечает, что проверка выключена.
			GROUPBASE_UPDATE_CHECK: 'false'
		}
	}
});
