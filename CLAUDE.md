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
├─ backup/     копии (VACUUM INTO + файлы + ключи + вход CloudPub), облачные папки, восстановление при старте
├─ hosts/      сайт на нескольких компьютерах хоста: общая папка в облаке, снимки, кто хост, ожидание
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

## Несколько компьютеров хоста

- `hosts/`: `HostService` (роль: off, host, checking, standby, waiting, switching; шаг раз в 5 с),
  `HostPlan` (чистое решение по записи о хосте и снимкам), `SiteFolder` (общая папка
  `<облако>/groupbase-site/<id>/`: `host.json`, `request.json`, `snapshots/s-поколение-время-комп.zip`,
  зеркало `files/`, `avatars/`), `SiteSnapshot` (снимок: база + ключи + вход CloudPub + список файлов),
  `HostSwitch.prepare` (до старта сервера в `DesktopCommand`: берёт данные, если хост закрыт или
  перенос заказан), `HostsConfig` (`hosts.properties` этого компьютера, в копии не попадает).
- Поколение (epoch) растёт при каждом переходе сайта на другой компьютер; данные старшего поколения
  уступают. После перехода номера журнала `changes` сдвигаются на 1000 (телефоны не должны принять
  новые изменения за полученные).
- Не хост: `AccessService.suspend()` (туннель не поднимается), `StandbyFilter` отвечает `standby` на
  весь API, кроме `/api/host`, `/api/health`, `/api/desktop/`; фронт уводит на `/standby`.
  «Перенести сюда» (`POST /api/host/takeover`) — с этого компьютера и без входа (войти по паролю в
  ожидании нельзя). Кто отвечает по адресу сайта — `GET /api/host/whoami` (без имён).
- Оболочка перед остановкой ради обновления шлёт `updating`: хост пишет «restarting», и сайт не
  уезжает на другой компьютер на эти полминуты. В событии `access` может быть `label` для меню.
- Проверять на одной машине двумя серверами можно (`GROUPBASE_HOSTS_CLOUD_ROOT`), но окна
  нужно открывать по очереди: cookie не различают порты, и страница ожидания одного сервера стирает
  сессию другого.
- Перенос по коду (без облачной папки): `TransferService` (код 12 знаков на 15 минут, 5 ошибок —
  код сгорел; отдаёт ту же копию, что `BackupService.write`, пока идёт — `HostService.lockWrites`:
  изменения отвечают 503 `moving`), `TransferClient` (новый компьютер: CSRF-cookie с
  `/api/health`, `POST /api/host/transfer` → zip, `/transfer/confirm`; http — только в локальной
  сети), `TransferController` (`/api/host/transfer/code` — администратор, `/api/host/transfer` и
  `/confirm` — без входа, по коду; `/api/host/pull` — с этого компьютера). На первом запуске —
  `POST /api/setup/transfer?code=` (код настройки). После подтверждения старый компьютер — роль
  `moved` (`hosts.properties: moved`, переживает перезапуск; `POST /api/host/return` — вернуть).

## Интерфейс: что где

- Иконки предметов — `web/src/lib/subjectIcons.ts` (ключи = `subjects.icon`, подбор по названию).
- Просмотр файлов — `lib/files/FileViewer.svelte` (`openFiles()` из `viewer.svelte.ts`), PDF через
  pdf.js (`PdfView.svelte`, воркер кешируется лениво — см. `LAZY` в service-worker).
- Фон входа — `lib/appearance.ts` + классы `.login-bg-*` в `app.css`; сервер — `avatars/LoginBackground`.
- Свои настройки — `/profile` (шестерёнка у имени в боковой панели; на телефоне — «Профиль»):
  разделы «Аккаунт» (`lib/profile/AccountPanel`, `SecurityPanel`, `DataPanel`) и «Приложение»
  (`ThemePicker`, `settings/NotificationSettings`, `settings/OfflineSettings`, `profile/AppPanel`),
  `?tab=…`; старые ссылки `/profile#notifications` переводятся сами. Управление группой и сайтом —
  `/settings`, в интерфейсе **«Управление»**. Карточки разделов — класс `.card.pane` (и
  `.pane-title`), заголовки разделов — `ui/SectionHead.svelte`; ничего не должно быть шире экрана
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
- Темы: режим — `data-theme` (lib/theme.ts). Цвет — `lib/colors.ts`: оттенок и насыщенность →
  переменные `--pal-…` (светлый) и `--pald-…` (тёмный) и `--mesh-1..3` через OKLCH (с уменьшением
  насыщенности до видимого цвета), inline-стилем на `<html>` + атрибут `data-accent`. В app.css
  базовые токены берут их с запасным значением «Классики» («Чернила» — без переменных). Готовая
  строка — `gb-accent-css` в localStorage, её ставит скрипт в app.html до отрисовки (только
  `--имя:#цвет`). Прежние `gb-palette` переводятся в цвет при старте (`migrateAccent`).
- «Управление» в навигации — только если `hasSettings()` (session.svelte.ts; `view_audit` не
  считается — модератору пункт не нужен, его журнал в «Модерации»); навигацию фильтрует
  `visibleNav()`. В боковой панели нет «Файлов» и «Уведомлений» (колокольчик — на «Сегодня»),
  «Сессия» — по `sessionNavVisible()` (lib/content/session.ts, `study_groups.session_nav`: auto —
  около сессии по датам, show, hide; выбирает староста в «Управление → Семестр»). Палитра (Ctrl K,
  на Mac ⌘K — `lib/platform.ts shortcut()`) и горячие клавиши знают все разделы; «?» в пустой
  палитре — окно «Горячие клавиши» (`palette.help`).
- «Режим управления» переключает только администратор сайта (`canToggleManage()`); у остальных
  `manageMode()` всегда true, даже если на сервере сохранено «выключен».
- Дизайн — `data-style` на `<html>` (lib/theme.ts `STYLES`, ровно пять: plain «Классика» без
  атрибута, glass, depth, neon «Сияние», paper), правила — в app.css (`:root[data-style]`, слой
  `body::before`); у `.card` и `.list`. Старые значения (tint, outline, comic) просто не
  применяются. «Оформление под значок» убрано по просьбе владельца.
- Блокировать, исключать и удалять людей (`block_users`) могут только администратор и староста —
  у модератора этого права нет (Rbac.MODERATOR). В интерфейсе роли — «Администратор» и
  «Модератор», без «сайта».
- Иконки (`/icons/*`, favicon) кешируются на месяц: при смене картинки — новый `?v=` в app.html,
  manifest.webmanifest и service-worker.ts.
- Логотип — `web/static/logo.svg` (элементы по id: globe, grid, figure, eye; белые линии рассчитаны
  на белый фон). Из него: `favicon.svg` (тот же, кадр крупнее), PNG-иконки — `java scripts/Icons.java`
  (и `desktop` — исходники для `npm run icons` в desktop/ и слой `AppIcon.icon/Assets/logo.png`:
  значок macOS 26+ в формате Icon Composer — в тёмном, прозрачном и тонированном Dock логотип белый).
  tauri не собирает `.icon` сам (actool падает, tauri-apps/tauri#15315): `Assets.car` собирает
  проверка `.github/workflows/icon.yml` на Xcode 26 (артефакт, там же предпросмотр оформлений) — он
  лежит в `desktop/src-tauri/icons` и указан в `bundle.icon`; поменяли `.icon` — замените и его. Копии в заставках `app.html` и
  `desktop/ui/index.html` (тест `logo.spec.ts` сверяет их с logo.svg), страница ошибки берёт части
  через `<use href="/logo.svg#…">`.
- Никаких `confirm()`, `prompt()`, `alert()`: окно хоста на Mac (WKWebView) их не показывает, а
  плагин диалогов Tauri подменяет `confirm()` асинхронным — проверка получала «да». Вместо них —
  `ask()` и `askText()` из `lib/ui/ask.svelte.ts` (окно `ui/Dialogs.svelte` грузится при первом
  вопросе).
- `ui/Modal.svelte`: у окон с вводом — `dirty` (и `dirtyText`): клик мимо окна, Esc и крестик
  сначала спрашивают «Закрыть без сохранения?»; клик считается «мимо», только если и нажатие было
  на затемнении (выделение текста с выходом за окно его не закрывает).
- Короткие тексты с предлогами — через `lib/typo.ts` (неразрывные пробелы после «в», «с», «на» и
  перед тире).
- Журнал действий — `lib/settings/Audit.svelte` + подписи `settings/auditLabels.ts` (новое действие
  в `audit.log` — добавьте подпись; без неё — общая фраза по разделу). Название записи — из
  `details` (`title`, `name`, `text`).
- «Не мой предмет» (подгруппы): таблица `subject_hidden`, `PUT /api/subjects/{id}/mine`, поле
  `Subject.mine`. Скрытое не попадает в общие списки заданий, новостей, недавних материалов
  (сервер — при `subject == null`; офлайн-копия — `offline/local.ts notMine`) и в уведомления
  (`Notifier.followers`, `Reminders`). Выбор подгруппы — `lib/content/subgroups.ts` (номер
  подгруппы в названии: «№1», «1 подгруппа», «(2)») и карточка `SubgroupChoice` на «Сегодня» и в
  «Предметах»; «Хожу на все» — localStorage `gb-subgroups-ok`.
- Фото и файлы в новостях: `post_attachments`, `NewsService.setAttachments` (файл — свой и нигде не
  прикреплён: `MaterialService.used`), читать файл может тот, кто видит новость
  (`MaterialService.canRead`), в карточке — `content/NewsFiles.svelte` (лениво). `DropZone shrink`
  уменьшает фото до 2048 точек перед отправкой. «Загрузить файл» — `content/UploadPicker.svelte`,
  одно окно на приложение в макете (`palette.upload`).
- Большие мониторы: с 1440 и 1920 px — шире `--content` и `--sidebar`, крупнее текст (app.css);
  «Сегодня» (`.dash`) и лента новостей — в две колонки; новость и задание целиком — узкой колонкой
  (`main.narrow`).
- Скорость на телефоне (туннель — HTTP/1.1, по 6 запросов за раз): код обоих макетов со всем, что
  они импортируют, — одним файлом (`shell`), наше общее для 6+ страниц — ещё одним (`common`,
  `vite.config.ts`); `app.html` сразу запрашивает `/api/me`, `/api/subjects` и «Сегодня» (адрес — в
  localStorage `gb-today`), `request()` забирает эти ответы (`lib/early.ts`). Чтение, которое знает
  копия на устройстве, ждёт сеть 1,5 с, дальше — копия, а свежий ответ приходит следом (`api.ts`,
  `hasFresh`; не после своего изменения). Реактивное состояние внутри `api()` читается через `untrack`
  — иначе `$effect` страницы перезапускается от него.
- Service worker: при установке — только страница и статика (`CORE`) плюс код прежней версии с тем
  же хешем (из её кеша); остальное — фоном по два файла по сообщению `warm` от страницы (после
  загрузки и после смены версии), в конце — метка `/__complete`. С меткой страница отдаётся из кеша
  без сети, без неё — из сети. Кеш прежней версии живёт до следующего обновления (старая открытая
  страница догружает свой код); статику и страницу берём только из кеша своей версии.
- На телефоне нижняя панель — те же разделы, что в боковой (`bottomNav`: Сегодня, Новости, ДЗ,
  Предметы, Профиль), поиск — в `MobileBar`, участники, сессия, уведомления и настройки — в профиле.
- «Управление → Версия и обновления» (`lib/settings/UpdatesPanel.svelte`, `POST
  /api/admin/update-check`): GitHub сразу (`UpdateCheck.checkNow`, ответ с причиной ошибки), в
  приложении хоста — ещё и оболочка. В e2e проверка выключена (`GROUPBASE_UPDATE_CHECK=false`).
- «Модерация» (`routes/(app)/moderation`, пакет `moderation`, `/api/moderation/*`): жалобы (таблица
  `reports`, `POST /api/reports`, от человека на одно — одна открытая; кто пожаловался, модераторы не
  видят), материалы на проверке, свежие и скрытые записи и комментарии, журнал (действия из
  `ModerationService.LOG_ACTIONS`). Доступ — у кого `moderate_content` хоть в одной группе;
  скрывают и удаляют сервисы новостей, заданий, материалов и комментариев (у комментария —
  `PUT /api/comments/{id}/hidden`). Число в меню — `lib/moderation.svelte.ts`, действия и
  «Пожаловаться» — `lib/content/moderate.ts`. Уведомления о жалобах и материалах на проверку —
  и модераторам сайта (`Notifier.moderators`).
- Живые обновления: `sync/LiveUpdates` (SSE `/api/live`, раз в секунду смотрит `MAX(seq)` журнала
  `changes`; в потоке только номер, данные — обычными запросами; `bell` — кому пришло уведомление).
  Фронт — `lib/live.ts` (грузится отдельно): пока вкладка видна, по событию — `syncNow()` или
  `offline.version++`; нет «hello» за 8 с (туннель копит ответ) — опрос раз в 20 с. `LiveUpdates` —
  `SmartLifecycle`: закрывает соединения до «вежливой» остановки Tomcat (иначе ждала бы до 20 с).
- Оформление на устройстве (`lib/looks.ts`, выбор — `shell/ThemePicker`): своя картинка на фоне —
  `data-bg="custom"` на `<html>` и слой `:root[data-bg]::before` в app.css (`--bg-image`, data: URL
  в localStorage), значок — `lib/appIcon.svelte.ts` (варианты — `java scripts/Icons.java variants`:
  `static/icons/v/*`, `manifest-*.webmanifest`). Всё применяет скрипт в app.html до отрисовки.
- Фон карточки предмета: `subjects.cover`, `AvatarService.storeCover` (16:9, 1280 и 480, WebP),
  `PUT/DELETE /api/subjects/{id}/cover`; `SubjectArt` показывает его вместо иконки.
- Знакомство после регистрации: `users.onboarded_at` (у прежних — заполнено миграцией),
  `me.user.onboarded`, окно — `lib/Welcome.svelte` (открывается после ухода заставки: событие
  `gb:splash-gone`), состояние — `lib/onboarding.svelte.ts`. В e2e его закрывают 01 и 03 — дальше не
  мешает.
- Бюджет 100 КБ: редкое на тяжёлых страницах — лениво (добавление людей, QR-код, поля нового пароля,
  разбор офлайн-копии `offline/local.ts`, живые обновления).
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
