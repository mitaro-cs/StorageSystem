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
| один статический бинарник | fat-jar + jlink-рантайм (`scripts/jre.sh`) внутри приложения хоста (Tauri) |
| GoReleaser | GitHub Actions `release.yml`: тег `v*` → .dmg (Apple Silicon, Intel), установщик .exe, jar |
| distroless/static < 30 МБ | distroless/base + jlink-JRE (~100 МБ; JVM требует glibc) |

## Архитектура

```
src/main/java/app/groupbase/
├─ cli/        точка входа Main: serve, desktop (сервер в приложении хоста) и обслуживание —
│              init, user, backup, restore, doctor, seed (профиль cli, общий -d/--data — Target)
├─ config/     GroupbaseProperties, загрузка groupbase.toml, генерация секретов
├─ store/      DataSource, Flyway, репозитории (JdbcClient)
├─ auth/       Argon2id, сессии, TOTP, passkeys (WebAuthn без библиотек), RBAC, rate limit
├─ web/        фильтры (заголовки, CSRF, сессия), контроллеры /api, SPA-фолбэк
├─ files/      AES-256-GCM, хранение по UUID, MIME по magic bytes
├─ avatars/    ресайз, WebP, удаление EXIF, SVG с инициалами
├─ content/    предметы, новости, ДЗ, материалы, комментарии
├─ search/ notify/ audit/ export/ sync/
├─ desktop/    связь с оболочкой приложения (события @gb в stdout, команды в stdin), порт и сеть
├─ access/     доступ для группы: туннель fxTunnel (клиент скачивается с проверкой SHA-256), LAN, свой адрес
├─ backup/     копии (VACUUM INTO + файлы + ключи), облачные папки, восстановление при старте
├─ status/     проверка обновлений
└─ jobs/       очистка сессий, ссылок и IP в аудите
web/           SvelteKit (adapter-static, SPA) → web/build → classpath:/static в jar
desktop/       приложение хоста на Tauri 2 (Rust): окно, трей, запуск Java из resources/ (jlink + jar)
docs/          документация на русском (desktop.md — для хоста)
```

Модель: инстанс (в интерфейсе — «сайт») → группы → предметы (`subject_groups`, общий предмет связан с несколькими группами).
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
make desktop    # установщик приложения хоста для этой ОС (нужен Rust)
make desktop-run # приложение из исходников, данные в ./data-desktop
java -jar target/groupbase.jar serve --config groupbase.toml
java -jar target/groupbase.jar doctor -d ./data-dev   # проверка данных без изменений
```

Порядок перед пушем (CI проверяет то же): `./mvnw spotless:check verify`, в `web/` — `npm run lint`,
`npm run check`, `npm run build` и `node scripts/bundle-size.mjs 102400` (самая тяжёлая страница
≤ 100 КБ gzip; редкое — через `{#await import(...)}`), `npx playwright test` против свежего jar.

Бэкенд без фронта собирается и работает (отдаёт заглушку). Требуется JDK 21+ и Node 22+;
для приложения хоста — Rust (rustup) и `scripts/desktop-resources.sh` (кладёт jlink-Java и jar в
`desktop/src-tauri/resources`).

## Приложение хоста

- Оболочка (`desktop/src-tauri/src/main.rs`) запускает `java -jar groupbase.jar desktop --data <каталог>`
  и читает события: `ready` (адрес и одноразовая ссылка входа окна), `access` (адрес для группы в трее),
  `restart`, `status`, `error`. Код выхода 3 — перезапуск (восстановление, смена сети).
- Окно хоста ходит на `http://127.0.0.1:порт`, сессия «локальная» (`sessions.local`): без 2FA, только с
  этого компьютера. Участники — через туннель по HTTPS; Secure у cookie и HSTS — по схеме запроса.
- Ответы сервера помечены `X-Groupbase: 1`: без метки (страница туннеля при выключенном компьютере) фронт
  и service worker считают сервер недоступным и показывают сохранённое.
- Ссылки и QR строятся от `instance.publicUrl` (`lib/copy.ts → siteUrl()`), а не от `location.origin`.
- Окно не перехватывает перетаскивание файлов (`disable_drag_drop_handler`) — иначе DropZone их не
  получит.
- Окно хоста без service worker: интерфейс регистрирует его сам (`registerServiceWorker`, в
  `vite.config.ts` `serviceWorker.register: false`) и не в окне хоста. `/api/desktop/enter` отвечает
  страницей, а не 302: перенаправление service worker видит «непрозрачным» (`opaqueredirect`), и
  старые версии зацикливали вход. Страница подключает `static/host-window.js` (только для окна на
  компьютере хоста) — он убирает service worker, кеши и копию данных. `scripts/desktop-smoke.sh`
  запускает приложение дважды и ловит зацикливание.
- Проверочные сборки приложения — только с другим идентификатором (`--config
  '{"identifier":"app.groupbase.uptest","productName":"groupbase-uptest"}'`): с тем же `app.groupbase`
  они делят с настоящим приложением хранилище окна (WebKit), блокировку второго запуска и порт.
- Обновление: оболочка (tauri-plugin-updater, `latest.json` выпуска) проверяет через 20 с после запуска
  и раз в 6 ч, сообщает серверу `update-available X` в stdin (и заново — после перезапуска сервера) и
  один раз за запуск сама спрашивает «Обновить сейчас?». Сервер, не зная версии, просит проверить
  событием `check-update`. «Обновить сейчас» в «Состоянии» → событие `update` → оболочка сама
  проверяет, скачивает, останавливает сервер, ставит и перезапускается; при ошибке окно возвращается
  на сайт. Кнопка в окне хоста есть всегда, когда версия известна (от оболочки или GitHub).

## Интерфейс: что где

- Иконки предметов — `web/src/lib/subjectIcons.ts` (ключи = `subjects.icon`, подбор по названию).
- Просмотр файлов — `lib/files/FileViewer.svelte` (`openFiles()` из `viewer.svelte.ts`), PDF через
  pdf.js (`PdfView.svelte`, воркер кешируется лениво — см. `LAZY` в service-worker).
- Фон входа — `lib/appearance.ts` + классы `.login-bg-*` в `app.css`; сервер — `avatars/LoginBackground`.
- Разделы настроек и профиля оформляются `ui/SectionHead.svelte`; ничего не должно быть шире экрана
  телефона (e2e `14-files-people` проверяет `scrollWidth`).
- Типы заданий — `lib/content/kinds.ts` (`homework.kind`: homework, lab, test, credit, exam; у зачёта
  и экзамена есть `place`). Режим «Сессия» — `lib/content/session.ts` (расчёты), страница
  `routes/(app)/session`, даты — `study_groups.session_from/to` (`MeGroup.session`), зачёты и
  экзамены — `view=exams` и `Today.exams`. Мастер «Новый семестр» — `lib/content/NewSemester.svelte`.
- Passkeys — `auth/WebAuthn.java` (разбор и подпись: ES256, Ed25519, RS256), `auth/Passkeys.java`
  (вызовы в памяти на 5 минут, пароль при добавлении), фронт — `lib/auth/passkey.ts`. Открытый ключ
  берётся из `getPublicKey()` браузера — CBOR не нужен. Адрес сайта: `instance.publicUrl` или адрес
  запроса, не IP (WebAuthn требует имени). e2e `16-passkeys` — виртуальный ключ Chromium через CDP.
- `Permissions-Policy`: камера разрешена своей странице (сканер QR в профиле) — не возвращать
  `camera=()`.
- Меню действий (`ui/Menu.svelte`) открывается в верхнем слое (Popover API) и ставится расчётом
  `ui/menuPlace.ts`: у нижнего края — вверх, всегда целиком на экране, при прокрутке едет за кнопкой.
  Не возвращать `position: absolute` внутри карточек с `overflow: hidden` — меню обрезалось.
- Темы: режим — `data-theme` (lib/theme.ts), цвет — `data-palette` на `<html>`. В app.css базовые
  токены берут `var(--pal-…)` (светлый) и `var(--pald-…)` (тёмный) с запасным значением «Классики»,
  палитра задаёт только их. Применяется до отрисовки скриптом в app.html, выбор — `shell/ThemePicker`.
- «Настройки» в навигации — только если `hasSettings()` (session.svelte.ts); навигацию фильтрует
  `visibleNav()`. В боковой панели нет «Файлов» и «Уведомлений» (колокольчик — на «Сегодня»),
  «Сессия» — по `sessionNavVisible()` (lib/content/session.ts, `study_groups.session_nav`: auto —
  около сессии по датам, show, hide; выбирает староста в «Настройки → Семестр»). Палитра ⌘K и
  горячие клавиши знают все разделы.
- Стиль оформления — `data-style` на `<html>` (lib/theme.ts `STYLES`: depth, glass, tint; «Обычный»
  — без атрибута), правила — в app.css и в NewsCard/HomeworkRow (цвет предмета — `--subject`).
  Каждый выбирает себе; по умолчанию вид не меняется.
- Блокировать, исключать и удалять людей (`block_users`) могут только администратор и староста —
  у модератора сайта этого права нет (Rbac.MODERATOR).
- Иконки (`/icons/*`, favicon) кешируются на месяц: при смене картинки — новый `?v=` в app.html,
  manifest.webmanifest и service-worker.ts.
- Логотип — `web/static/logo.svg` (элементы по id: globe, grid, figure, eye; белые линии рассчитаны
  на белый фон). Из него: `favicon.svg` (тот же, кадр крупнее), PNG-иконки — `java scripts/Icons.java`
  (и `desktop` — исходники для `npm run icons` в desktop/), копии в заставках `app.html` и
  `desktop/ui/index.html` (тест `logo.spec.ts` сверяет их с logo.svg), страница ошибки берёт части
  через `<use href="/logo.svg#…">`.
- Никаких `confirm()`, `prompt()`, `alert()`: окно хоста на Mac (WKWebView) их не показывает, а
  плагин диалогов Tauri подменяет `confirm()` асинхронным — проверка получала «да». Вместо них —
  `ask()` и `askText()` из `lib/ui/ask.svelte.ts` (окно `ui/Dialogs.svelte` грузится при первом
  вопросе).
- iPhone, установленный сайт: после клавиатуры iOS 26–27 сдвигает видимую область относительно
  position: fixed (WebKit 297779). `lib/shell/viewport.ts` считает сдвиг (`--vv-shift`, класс
  `kb-open` — печатают); нижние фиксированные элементы берут `translate: 0 var(--vv-shift, 0px)`.
- Web Push: контакт в подписи VAPID (`sub`) — адрес сайта https или страница проекта, никогда не
  mailto на localhost/IP: служба Apple отвечает 403 BadJwtToken (`VapidKeys.subject`). «Проверить»
  возвращает код и причину службы (`PushSender.Report`).
- Ключи входа: подсказка в поле логина (`mediation: 'conditional'`, у полей `autocomplete="…
  webauthn"`), кнопка её отменяет. «Ключ безопасности» — `authenticatorAttachment: cross-platform`
  и `hints: ['security-key']`.
- У каждого пакета сервера — свои тесты (`EveryPackageHasTestsTest`).
