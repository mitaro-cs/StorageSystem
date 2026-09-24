# groupbase — основные команды. Нужны JDK 21+ и Node 22+.
MVN      ?= ./mvnw -B -q
NPM      ?= npm --prefix web
JAR      := target/groupbase.jar
DEV_DATA := ./data-dev

.PHONY: help dev dev-backend dev-web web build test test-java test-web lint lint-java lint-web fmt seed e2e run clean package desktop desktop-run

help: ## Список команд
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*## "}{printf "  %-12s %s\n",$$1,$$2}'

web/node_modules: web/package-lock.json
	$(NPM) ci
	@touch web/node_modules

web: web/node_modules ## Собрать фронтенд в web/build
	$(NPM) run build

build: web ## Собрать jar со встроенным фронтом
	$(MVN) -DskipTests package

dev: ## Бэкенд :8080 + vite :5173 (открывайте :5173)
	@$(MAKE) -j2 dev-backend dev-web

dev-backend:
	GROUPBASE_DATA_DIR=$(DEV_DATA) GROUPBASE_HTTP_INSECURE=true LOGGING_STRUCTURED_FORMAT_CONSOLE= \
		$(MVN) spring-boot:run -Dspring-boot.run.arguments=serve

dev-web: web/node_modules
	$(NPM) run dev

test: test-java test-web ## Все тесты

test-java:
	$(MVN) verify

test-web: web/node_modules
	$(NPM) run test:unit -- --run

lint: lint-java lint-web ## Линтеры и проверка типов

lint-java:
	$(MVN) spotless:check compile

lint-web: web/node_modules
	$(NPM) run lint
	$(NPM) run check

fmt: web/node_modules ## Автоформатирование
	$(MVN) spotless:apply
	$(NPM) run format

seed: ## Демо-данные в ./data-dev (пароль у всех: demo-password)
	$(MVN) -DskipTests package
	GROUPBASE_DATA_DIR=$(DEV_DATA) java -jar $(JAR) seed

e2e: build ## Playwright против собранного jar
	$(NPM) run test:e2e

run: build ## Запустить собранный jar
	GROUPBASE_DATA_DIR=$(DEV_DATA) GROUPBASE_HTTP_INSECURE=true java -jar $(JAR) serve

desktop: build ## Приложение хоста для этой ОС (нужен Rust): .dmg / установщик .exe
	scripts/desktop-resources.sh
	cd desktop && npm ci && npx tauri build

desktop-run: build ## Запустить приложение хоста из исходников (данные — в ./data-desktop)
	scripts/desktop-resources.sh
	cd desktop && npm ci && GROUPBASE_DATA=$(CURDIR)/data-desktop npx tauri dev

clean:
	rm -rf target web/build web/.svelte-kit dist desktop/src-tauri/target desktop/src-tauri/resources
