# groupbase — заметки для Claude Code

Self-hosted сервис для студенческих групп: новости, ДЗ, материалы по предметам. Один экземпляр (инстанс)
обслуживает одну группу (`single`) или несколько (`multi`, например поток). Всё на русском: интерфейс,
документация, сообщения об ошибках API.

## Стек и отличия от исходного промта

Промт писался под Go, но владелец выбрал **Java 21 + Spring Boot 4**. Соответствие:

| В промте | Здесь |
|---|---|
| Go + chi | Spring Boot 4 (Web MVC, Tomcat, виртуальные потоки) |
| `database/sql` + modernc sqlite | `org.xerial:sqlite-jdbc` + HikariCP, WAL, `foreign_keys`, `busy_timeout=5000` |
| goose + embed | Flyway, миграции в `src/main/resources/db/migration` |
| sqlc | `JdbcClient` с явным SQL и ручными `RowMapper`, без ORM |
| cobra | picocli (`app.groupbase.cli`) |
| slog JSON | структурные логи Spring Boot (logstash JSON), без персональных данных |
| bluemonday | OWASP Java HTML Sanitizer + commonmark-java |
| один статический бинарник | fat-jar + jlink-рантайм: архив `bin/groupbase` для linux/amd64, linux/arm64, windows/amd64, darwin/arm64 |
| GoReleaser | GitHub Actions matrix + `scripts/package.sh` (jlink) |
| distroless/static < 30 МБ | distroless/base + jlink-JRE (~100 МБ; JVM требует glibc) |

## Архитектура

```
src/main/java/app/groupbase/
├─ cli/        точка входа Main, команды serve/init/deploy/backup/restore/user/doctor
├─ config/     GroupbaseProperties, загрузка groupbase.toml, генерация секретов
├─ store/      DataSource, Flyway, репозитории (JdbcClient)
├─ auth/       Argon2id, сессии, TOTP, RBAC (Permission, матрица, переопределения), rate limit
├─ web/        фильтры (заголовки, CSRF, сессия), контроллеры /api, SPA-фолбэк
├─ files/      AES-256-GCM, хранение по UUID, MIME по magic bytes
├─ avatars/    ресайз, WebP, удаление EXIF, SVG с инициалами
├─ content/    предметы, новости, ДЗ, материалы, комментарии
├─ search/ notify/ audit/ export/
├─ jobs/       бэкапы, очистка сессий и IP в аудите
└─ deploy/     мастер развёртывания и шаблоны
web/           SvelteKit (adapter-static, SPA) → web/build → classpath:/static в jar
deploy/        Dockerfile, compose, Caddyfile, systemd
docs/          документация на русском
```

Модель: инстанс → группы → предметы (`subject_groups`, общий предмет связан с несколькими группами).
Роли инстанса: `admin`, `moderator` (в `users.instance_role`). Роли группы: `headman`, `deputy`, `student`
(в `memberships`). Права: `auth.Permission` + матрица по умолчанию `auth.Rbac` + `group_permission_overrides`
(только ячейки, помеченные ⚙ в промте).

## Соглашения

- Проверка прав только через `@Require(Permission.X)` на методе контроллера (группа берётся из `{groupId}` в пути)
  или `Authz.require(...)` в сервисе, когда группы приходят в теле запроса. Никаких `if (role == ...)` в контроллерах.
- Время в БД и API — миллисекунды Unix (`INTEGER`), UTC. Часовой пояс для отображения — `groupbase.timezone`.
- Ошибки API: `{"error": "код", "message": "текст по-русски"}` через `ApiException`.
- Мутирующие запросы требуют заголовок `X-CSRF-Token`, равный cookie `gb_csrf`.
- Никаких внешних запросов из фронта: шрифты, иконки, всё — локально. CSP `default-src 'self'`.
- Логи не содержат имён, IP, токенов. Пароли и токены никогда не логируются.
- Коммиты — Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`, `test:`).
- Новая миграция — новый файл `V<N>__описание.sql`, старые не редактировать.
- Строки интерфейса — в `web/src/lib/i18n/ru.ts`.

## Команды

```
make dev        # бэкенд на :8080 + vite dev на :5173 (прокси /api)
make build      # web → jar (target/groupbase.jar)
make test       # ./mvnw test + vitest
make lint       # spotless:check + javac -Werror + eslint + prettier + svelte-check
make fmt        # автоформатирование
make seed       # демо-данные в ./data-dev
make e2e        # Playwright против собранного jar
java -jar target/groupbase.jar serve --config groupbase.toml
```

Бэкенд без фронта собирается и работает (отдаёт заглушку). Требуется JDK 21+ и Node 22+.
