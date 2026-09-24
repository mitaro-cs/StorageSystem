// Приложение хоста groupbase: окно с сайтом группы, значок в трее и сервер (Java) внутри.
//
// Сервер — дочерний процесс `java -jar groupbase.jar desktop --data <каталог>`. Он пишет в stdout
// события `@gb {...}` (ready, enter, access, restart, status, error) и принимает команды в stdin
// (`enter`, `quit`). Код выхода 3 — «перезапусти меня» (восстановление из копии, смена сети).
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use serde::Serialize;
use serde_json::Value;
use std::fs::{self, File, OpenOptions};
use std::io::{BufRead, BufReader, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};
use tauri::menu::{CheckMenuItem, Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::webview::{DownloadEvent, NewWindowResponse, PageLoadEvent};
use tauri::{
    AppHandle, Emitter, Manager, RunEvent, Url, WebviewUrl, WebviewWindowBuilder, WindowEvent, Wry,
};
use tauri_plugin_autostart::{MacosLauncher, ManagerExt};
use tauri_plugin_clipboard_manager::ClipboardExt;
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons};
use tauri_plugin_opener::OpenerExt;
use tauri_plugin_updater::UpdaterExt;

const MAIN: &str = "main";
const RESTART_CODE: i32 = 3;
const HIDDEN_ARG: &str = "--hidden";

/// Что показывает заставка: ход запуска или ошибка с кнопкой «Перезапустить».
#[derive(Clone, Serialize, Default)]
struct Status {
    text: String,
    error: bool,
}

#[derive(Default)]
struct Server {
    child: Option<Arc<Mutex<Child>>>,
    stdin: Option<ChildStdin>,
    /// Адрес сервера для окна: http://127.0.0.1:порт.
    local: Option<String>,
    /// Адрес сайта для группы (туннель, локальная сеть или свой).
    public: Option<String>,
    /// Окно надо впустить на сайт, когда его покажут (сервер стартовал при скрытом окне).
    needs_enter: bool,
    quitting: bool,
    status: Status,
}

struct App {
    server: Mutex<Server>,
    awake: Mutex<Option<keepawake::KeepAwake>>,
    copy_item: Mutex<Option<MenuItem<Wry>>>,
    update_item: Mutex<Option<MenuItem<Wry>>>,
    /// Найденная новая версия (для пункта меню и кнопки в «Состоянии»).
    update: Mutex<Option<String>>,
    updating: Mutex<bool>,
    data: PathBuf,
}

fn main() {
    let hidden = std::env::args().any(|a| a == HIDDEN_ARG);
    let app = tauri::Builder::default()
        // Второй запуск просто показывает уже открытое окно.
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            show_main(app)
        }))
        .plugin(tauri_plugin_autostart::init(
            MacosLauncher::LaunchAgent,
            Some(vec![HIDDEN_ARG]),
        ))
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_clipboard_manager::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_dialog::init())
        .invoke_handler(tauri::generate_handler![
            restart_server,
            open_logs,
            current_status
        ])
        .setup(move |app| {
            // GROUPBASE_DATA — другой каталог данных (разработка, тесты, несколько групп).
            let data = plain(&match std::env::var_os("GROUPBASE_DATA") {
                Some(dir) => PathBuf::from(dir),
                None => app.path().app_data_dir()?,
            });
            fs::create_dir_all(data.join("logs"))?;
            app.manage(App {
                server: Mutex::new(Server::default()),
                awake: Mutex::new(None),
                copy_item: Mutex::new(None),
                update_item: Mutex::new(None),
                update: Mutex::new(None),
                updating: Mutex::new(false),
                data,
            });
            create_window(app.handle(), !hidden)?;
            build_tray(app.handle())?;
            if read_pref(app.handle(), "keepAwake") {
                set_awake(app.handle(), true);
            }
            start_server(app.handle());
            schedule_update_checks(app.handle().clone());
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("не удалось запустить groupbase");

    app.run(|app, event| match event {
        // ⌘Q, «Выйти» в меню или завершение системы: сначала аккуратно останавливаем сервер.
        RunEvent::ExitRequested { api, .. } => {
            let quitting = app.state::<App>().server.lock().unwrap().quitting;
            if !quitting {
                api.prevent_exit();
                quit(app);
            }
        }
        #[cfg(target_os = "macos")]
        RunEvent::Reopen { .. } => show_main(app),
        _ => {}
    });
}

// ---------- окно ----------

fn splash_url() -> Url {
    #[cfg(windows)]
    let base = "http://tauri.localhost/index.html";
    #[cfg(not(windows))]
    let base = "tauri://localhost/index.html";
    Url::parse(base).expect("адрес заставки")
}

fn is_local_page(url: &Url) -> bool {
    url.scheme() == "tauri" || url.host_str() == Some("tauri.localhost")
}

fn create_window(app: &AppHandle, visible: bool) -> tauri::Result<()> {
    let nav = app.clone();
    let popup = app.clone();
    let downloads = app.clone();
    let window = WebviewWindowBuilder::new(app, MAIN, WebviewUrl::App("index.html".into()))
        .title("groupbase")
        .inner_size(1180.0, 800.0)
        .min_inner_size(380.0, 560.0)
        .visible(visible)
        // Файлы, перетащенные в окно, должна получить страница (зона «Выберите файл или
        // перетащите сюда»), а не оболочка: иначе вложение к заданию так не прикрепить.
        .disable_drag_drop_handler()
        // Внутри окна — только сайт группы и заставка; остальные ссылки — в браузере.
        .on_navigation(move |url| {
            let inside = is_local_page(url) || is_server_page(&nav, url);
            log(
                &nav,
                &format!(
                    "переход: {} → {}",
                    without_query(url),
                    if inside {
                        "в окне"
                    } else {
                        "в браузере"
                    }
                ),
            );
            if inside {
                return true;
            }
            let _ = nav.opener().open_url(url.as_str(), None::<&str>);
            false
        })
        .on_new_window(move |url, _features| {
            let _ = popup.opener().open_url(url.as_str(), None::<&str>);
            NewWindowResponse::Deny
        })
        // Скачанное (копии, архивы, файлы) — в «Загрузки», затем показываем файл.
        .on_download(move |_webview, event| {
            match event {
                DownloadEvent::Requested { destination, .. } => {
                    if let Ok(dir) = downloads.path().download_dir() {
                        let name = destination
                            .file_name()
                            .map(|n| n.to_owned())
                            .unwrap_or_else(|| "groupbase-download".into());
                        *destination = unique(dir.join(name));
                    }
                }
                DownloadEvent::Finished {
                    path: Some(path),
                    success: true,
                    ..
                } => {
                    let _ = downloads.opener().reveal_item_in_dir(path);
                }
                _ => {}
            }
            true
        })
        .on_page_load(|webview, payload| {
            if matches!(payload.event(), PageLoadEvent::Finished) {
                // После перенаправлений реальный адрес — у самого окна.
                let url = webview.url().unwrap_or_else(|_| payload.url().clone());
                log(
                    webview.app_handle(),
                    &format!("страница: {}", without_query(&url)),
                );
            }
        })
        .build()?;
    let w = window.clone();
    window.on_window_event(move |event| {
        if let WindowEvent::CloseRequested { api, .. } = event {
            // Закрыть окно ≠ выключить сайт: прячем окно, сервер работает дальше.
            api.prevent_close();
            let _ = w.hide();
        }
    });
    Ok(())
}

/// Адрес без параметров: в ссылке входа — одноразовый токен, в журнал он не попадает.
fn without_query(url: &Url) -> String {
    let mut u = url.clone();
    u.set_query(None);
    u.set_fragment(None);
    u.to_string()
}

/// Журнал оболочки (logs/shell.log): запуск и остановка сервера, открытые страницы.
fn log(app: &AppHandle, message: &str) {
    let Some(st) = app.try_state::<App>() else {
        return;
    };
    if let Ok(mut f) = OpenOptions::new()
        .create(true)
        .append(true)
        .open(st.data.join("logs").join("shell.log"))
    {
        let secs = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);
        let _ = writeln!(f, "{secs} {message}");
    }
}

fn is_server_page(app: &AppHandle, url: &Url) -> bool {
    let st = app.state::<App>();
    let server = st.server.lock().unwrap();
    match server.local.as_deref().and_then(|l| Url::parse(l).ok()) {
        Some(local) => url.host_str() == local.host_str() && url.port() == local.port(),
        None => false,
    }
}

/// Файл с таким именем уже есть — добавляем « (2)», « (3)»…
fn unique(path: PathBuf) -> PathBuf {
    if !path.exists() {
        return path;
    }
    let stem = path
        .file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or("file")
        .to_string();
    let ext = path
        .extension()
        .and_then(|s| s.to_str())
        .map(|e| format!(".{e}"))
        .unwrap_or_default();
    for i in 2.. {
        let candidate = path.with_file_name(format!("{stem} ({i}){ext}"));
        if !candidate.exists() {
            return candidate;
        }
    }
    unreachable!()
}

fn show_main(app: &AppHandle) {
    if let Some(w) = app.get_webview_window(MAIN) {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
    let st = app.state::<App>();
    let mut server = st.server.lock().unwrap();
    if server.needs_enter {
        server.needs_enter = false;
        send(&mut server, "enter");
    }
}

fn navigate(app: &AppHandle, url: &str) {
    if let (Some(w), Ok(u)) = (app.get_webview_window(MAIN), Url::parse(url)) {
        let _ = w.navigate(u);
    }
}

fn set_status(app: &AppHandle, text: &str, error: bool) {
    let status = Status {
        text: text.to_string(),
        error,
    };
    app.state::<App>().server.lock().unwrap().status = status.clone();
    let _ = app.emit_to(MAIN, "status", status);
}

/// Вернуть окно на заставку (перезапуск сервера, ошибка).
fn show_splash(app: &AppHandle, text: &str, error: bool) {
    set_status(app, text, error);
    if let Some(w) = app.get_webview_window(MAIN) {
        if !w.url().map(|u| is_local_page(&u)).unwrap_or(false) {
            let _ = w.navigate(splash_url());
        }
    }
}

// ---------- сервер ----------

fn runtime(app: &AppHandle) -> Result<(PathBuf, PathBuf), String> {
    // Для разработки: GROUPBASE_JAVA и GROUPBASE_JAR вместо встроенных.
    if let (Ok(java), Ok(jar)) = (
        std::env::var("GROUPBASE_JAVA"),
        std::env::var("GROUPBASE_JAR"),
    ) {
        return Ok((java.into(), jar.into()));
    }
    let res = plain(&app.path().resource_dir().map_err(|e| e.to_string())?);
    let java =
        res.join("runtime")
            .join("bin")
            .join(if cfg!(windows) { "java.exe" } else { "java" });
    let jar = res.join("groupbase.jar");
    if !java.exists() || !jar.exists() {
        return Err(format!(
            "Не найдены файлы сервера в {}. Переустановите приложение.",
            res.display()
        ));
    }
    // Приложение скачано из интернета и уже разрешено пользователем, но встроенная Java несёт
    // свой флаг карантина — снимаем его, чтобы macOS не спрашивала про неё отдельно.
    #[cfg(target_os = "macos")]
    let _ = Command::new("/usr/bin/xattr")
        .args(["-dr", "com.apple.quarantine"])
        .arg(res.join("runtime"))
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();
    Ok((java, jar))
}

/// Путь без префикса `\\?\` Windows: Tauri отдаёт каталог ресурсов через canonicalize(), а Java с
/// таким путём в classpath не находит классы в jar.
fn plain(path: &Path) -> PathBuf {
    dunce::simplified(path).to_path_buf()
}

fn start_server(app: &AppHandle) {
    set_status(app, "Запускаем сервер группы…", false);
    let st = app.state::<App>();
    let data = st.data.clone();
    let (java, jar) = match runtime(app) {
        Ok(p) => p,
        Err(e) => return show_splash(app, &e, true),
    };
    let java_log = OpenOptions::new()
        .create(true)
        .append(true)
        .open(data.join("logs").join("java.log"))
        .ok();
    let mut cmd = Command::new(&java);
    // Java на Windows читает аргументы в кодировке системы: путь с кириллицей в имени пользователя
    // может не дойти. Поэтому jar — относительно рабочего каталога, а каталог данных — через
    // переменную окружения (она всегда в Юникоде).
    if let Some(dir) = jar.parent() {
        cmd.current_dir(dir);
    }
    let jar_name = jar
        .file_name()
        .map(PathBuf::from)
        .unwrap_or_else(|| jar.clone());
    cmd.args([
        "-Xmx512m",
        "-XX:+UseSerialGC",
        "-XX:TieredStopAtLevel=1",
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "--enable-native-access=ALL-UNNAMED",
        "-jar",
    ])
    .arg(&jar_name)
    .arg("desktop")
    .env("GROUPBASE_DESKTOP_DATA", &data)
    .stdin(Stdio::piped())
    .stdout(Stdio::piped())
    .stderr(java_log.map(Stdio::from).unwrap_or_else(Stdio::null));
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    let mut child = match cmd.spawn() {
        Ok(c) => c,
        Err(e) => return show_splash(app, &format!("Не удалось запустить сервер: {e}"), true),
    };
    log(app, &format!("сервер запущен (pid {})", child.id()));
    let stdout = child.stdout.take();
    let child = Arc::new(Mutex::new(child));
    {
        let mut server = st.server.lock().unwrap();
        server.stdin = child.lock().unwrap().stdin.take();
        server.child = Some(child.clone());
        server.quitting = false;
    }
    if let Some(out) = stdout {
        let app = app.clone();
        thread::spawn(move || {
            for line in BufReader::new(out).lines().map_while(Result::ok) {
                if let Some(json) = line.strip_prefix("@gb ") {
                    if let Ok(v) = serde_json::from_str::<Value>(json) {
                        on_event(&app, &v);
                    }
                }
            }
        });
    }
    let app = app.clone();
    thread::spawn(move || watch(app, child));
}

/// Ждём завершения сервера: код 3 — перезапуск, иначе (если это не выход) — ошибка на заставке.
fn watch(app: AppHandle, child: Arc<Mutex<Child>>) {
    let code = loop {
        if let Ok(Some(status)) = child.lock().unwrap().try_wait() {
            break status.code();
        }
        thread::sleep(Duration::from_millis(250));
    };
    let st = app.state::<App>();
    log(&app, &format!("сервер остановился (код {code:?})"));
    let quitting = {
        let mut server = st.server.lock().unwrap();
        server.child = None;
        server.stdin = None;
        server.quitting
    };
    if quitting {
        return;
    }
    if code == Some(RESTART_CODE) {
        show_splash(&app, "Перезапускаем сервер…", false);
        start_server(&app);
        return;
    }
    let tail = log_tail(&st.data.join("logs").join("java.log"));
    let last = st.server.lock().unwrap().status.clone();
    let reason = if last.error {
        last.text
    } else {
        "Сервер группы неожиданно остановился.".into()
    };
    show_splash(
        &app,
        &format!(
            "{reason}{}",
            tail.map(|t| format!("\n\n{t}")).unwrap_or_default()
        ),
        true,
    );
}

fn on_event(app: &AppHandle, v: &Value) {
    let text = |k: &str| v.get(k).and_then(Value::as_str).unwrap_or("").to_string();
    match v.get("event").and_then(Value::as_str).unwrap_or("") {
        "ready" => {
            let enter = text("enter");
            let st = app.state::<App>();
            let visible = app
                .get_webview_window(MAIN)
                .and_then(|w| w.is_visible().ok())
                .unwrap_or(false);
            {
                let mut server = st.server.lock().unwrap();
                server.local = Some(text("url"));
                // Ссылка входа живёт две минуты: если окно скрыто, попросим новую, когда покажут.
                server.needs_enter = !visible;
            }
            set_status(app, "Готово", false);
            if visible {
                navigate(app, &enter);
            }
        }
        "enter" => navigate(app, &text("url")),
        "access" => {
            let url = text("url");
            let st = app.state::<App>();
            st.server.lock().unwrap().public = if url.is_empty() {
                None
            } else {
                Some(url.clone())
            };
            if let Some(item) = st.copy_item.lock().unwrap().as_ref() {
                let _ = item.set_enabled(!url.is_empty());
                let _ = item.set_text(if url.is_empty() {
                    "Доступ для группы выключен".to_string()
                } else {
                    format!("Скопировать ссылку: {}", short(&url))
                });
            };
        }
        "restart" => show_splash(app, "Перезапускаем сервер…", false),
        "status" => set_status(app, &text("message"), false),
        "error" => set_status(app, &text("message"), true),
        // «Обновить» в «Настройки → Сервер → Состояние».
        "update" => install_update(app),
        _ => {}
    }
}

fn short(url: &str) -> String {
    url.trim_start_matches("https://")
        .trim_start_matches("http://")
        .to_string()
}

fn send(server: &mut Server, command: &str) {
    if let Some(stdin) = server.stdin.as_mut() {
        let _ = writeln!(stdin, "{command}");
        let _ = stdin.flush();
    }
}

/// Выход: сервер завершается сам (закрывает базу и туннель), через 15 секунд — принудительно.
fn quit(app: &AppHandle) {
    let st = app.state::<App>();
    let child = {
        let mut server = st.server.lock().unwrap();
        if server.quitting {
            return;
        }
        server.quitting = true;
        send(&mut server, "quit");
        server.stdin = None;
        server.child.clone()
    };
    if let Some(w) = app.get_webview_window(MAIN) {
        let _ = w.hide();
    }
    let app = app.clone();
    thread::spawn(move || {
        if let Some(child) = child {
            let start = Instant::now();
            loop {
                let mut c = child.lock().unwrap();
                if matches!(c.try_wait(), Ok(Some(_))) {
                    break;
                }
                if start.elapsed() > Duration::from_secs(15) {
                    let _ = c.kill();
                    break;
                }
                drop(c);
                thread::sleep(Duration::from_millis(200));
            }
        }
        app.exit(0);
    });
}

fn log_tail(path: &Path) -> Option<String> {
    let mut f = File::open(path).ok()?;
    let len = f.metadata().ok()?.len();
    f.seek(SeekFrom::Start(len.saturating_sub(1500))).ok()?;
    let mut s = String::new();
    f.read_to_string(&mut s).ok()?;
    let lines: Vec<&str> = s.lines().rev().take(6).collect();
    if lines.is_empty() {
        return None;
    }
    Some(lines.into_iter().rev().collect::<Vec<_>>().join("\n"))
}

// ---------- команды заставки ----------

#[tauri::command]
fn restart_server(app: AppHandle) {
    let running = app.state::<App>().server.lock().unwrap().child.is_some();
    if !running {
        start_server(&app);
    }
}

#[tauri::command]
fn open_logs(app: AppHandle) {
    let dir = app.state::<App>().data.join("logs");
    let _ = app.opener().open_path(dir.to_string_lossy(), None::<&str>);
}

#[tauri::command]
fn current_status(app: AppHandle) -> Status {
    app.state::<App>().server.lock().unwrap().status.clone()
}

// ---------- трей ----------

fn build_tray(app: &AppHandle) -> tauri::Result<()> {
    let open = MenuItem::with_id(app, "open", "Открыть groupbase", true, None::<&str>)?;
    let copy = MenuItem::with_id(
        app,
        "copy",
        "Доступ для группы выключен",
        false,
        None::<&str>,
    )?;
    let autostart = CheckMenuItem::with_id(
        app,
        "autostart",
        "Запускать при входе в систему",
        true,
        app.autolaunch().is_enabled().unwrap_or(false),
        None::<&str>,
    )?;
    let awake = CheckMenuItem::with_id(
        app,
        "awake",
        "Не давать компьютеру засыпать",
        true,
        read_pref(app, "keepAwake"),
        None::<&str>,
    )?;
    let update_item = MenuItem::with_id(app, "update", "Проверить обновления", true, None::<&str>)?;
    let quit_item = MenuItem::with_id(
        app,
        "quit",
        "Выйти (сайт станет недоступен)",
        true,
        None::<&str>,
    )?;
    let menu = Menu::with_items(
        app,
        &[
            &open,
            &copy,
            &PredefinedMenuItem::separator(app)?,
            &autostart,
            &awake,
            &PredefinedMenuItem::separator(app)?,
            &update_item,
            &quit_item,
        ],
    )?;
    *app.state::<App>().copy_item.lock().unwrap() = Some(copy);
    *app.state::<App>().update_item.lock().unwrap() = Some(update_item);

    #[cfg(target_os = "macos")]
    let icon = tauri::image::Image::from_bytes(include_bytes!("../icons/tray-template.png"))?;
    #[cfg(not(target_os = "macos"))]
    let icon = app
        .default_window_icon()
        .cloned()
        .expect("значок приложения");

    let auto = autostart.clone();
    let aw = awake.clone();
    TrayIconBuilder::with_id("main")
        .icon(icon)
        .icon_as_template(cfg!(target_os = "macos"))
        .tooltip("groupbase — сервер группы")
        .menu(&menu)
        .show_menu_on_left_click(cfg!(target_os = "macos"))
        .on_menu_event(move |app, event| match event.id().as_ref() {
            "open" => show_main(app),
            "copy" => {
                let url = app.state::<App>().server.lock().unwrap().public.clone();
                if let Some(url) = url {
                    let _ = app.clipboard().write_text(url);
                }
            }
            "autostart" => {
                let manager = app.autolaunch();
                let on = !manager.is_enabled().unwrap_or(false);
                let _ = if on {
                    manager.enable()
                } else {
                    manager.disable()
                };
                let _ = auto.set_checked(manager.is_enabled().unwrap_or(on));
            }
            "awake" => {
                let on = !read_pref(app, "keepAwake");
                set_awake(app, on);
                write_pref(app, "keepAwake", on);
                let _ = aw.set_checked(on);
            }
            "update" => {
                let found = app.state::<App>().update.lock().unwrap().clone();
                match found {
                    Some(version) => ask_update(app, &version),
                    None => check_update(app, true),
                }
            }
            "quit" => quit(app),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            // Windows: щелчок по значку открывает окно (меню — правой кнопкой).
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                if !cfg!(target_os = "macos") {
                    show_main(tray.app_handle());
                }
            }
        })
        .build(app)?;
    Ok(())
}

// ---------- обновления ----------

/// Проверка обновлений: через 20 секунд после запуска и дальше раз в 6 часов.
fn schedule_update_checks(app: AppHandle) {
    thread::spawn(move || {
        thread::sleep(Duration::from_secs(20));
        loop {
            check_update(&app, false);
            thread::sleep(Duration::from_secs(6 * 3600));
        }
    });
}

/// interactive — пользователь сам нажал «Проверить»: ему ответят и «обновлений нет».
fn check_update(app: &AppHandle, interactive: bool) {
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let result = match app.updater() {
            Ok(updater) => updater.check().await,
            Err(e) => Err(e),
        };
        match result {
            Ok(Some(update)) => {
                let version = update.version.clone();
                remember_update(&app, Some(&version));
                if interactive {
                    ask_update(&app, &version);
                }
            }
            Ok(None) => {
                remember_update(&app, None);
                if interactive {
                    info(&app, "У вас последняя версия groupbase.");
                }
            }
            Err(e) => {
                log(&app, &format!("проверка обновлений: {e}"));
                if interactive {
                    info(
                        &app,
                        "Не удалось проверить обновления. Проверьте интернет и попробуйте позже.",
                    );
                }
            }
        }
    });
}

/// Запомнить найденную версию: пункт меню и сервер (кнопка «Обновить» в «Состоянии»).
fn remember_update(app: &AppHandle, version: Option<&str>) {
    let st = app.state::<App>();
    *st.update.lock().unwrap() = version.map(str::to_string);
    if let Some(item) = st.update_item.lock().unwrap().as_ref() {
        let _ = item.set_text(match version {
            Some(v) => format!("Обновить до версии {v}"),
            None => "Проверить обновления".to_string(),
        });
    }
    let mut server = st.server.lock().unwrap();
    send(
        &mut server,
        &format!("update-available {}", version.unwrap_or("")),
    );
}

fn info(app: &AppHandle, text: &str) {
    app.dialog().message(text).title("groupbase").show(|_| {});
}

fn ask_update(app: &AppHandle, version: &str) {
    let app2 = app.clone();
    app.dialog()
        .message(format!(
            "Вышла новая версия groupbase {version}. Обновить сейчас?\n\nСайт группы будет недоступен \
             около минуты, данные сохранятся."
        ))
        .title("Обновление groupbase")
        .buttons(MessageDialogButtons::OkCancelCustom(
            "Обновить".to_string(),
            "Позже".to_string(),
        ))
        .show(move |yes| {
            if yes {
                install_update(&app2);
            }
        });
}

/// Скачать новую версию, пока сайт работает, остановить сервер, установить и перезапуститься.
fn install_update(app: &AppHandle) {
    {
        let st = app.state::<App>();
        let mut updating = st.updating.lock().unwrap();
        if *updating {
            return;
        }
        *updating = true;
    }
    show_main(app);
    show_splash(app, "Скачиваем обновление…", false);
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let fail = |app: &AppHandle, why: String| {
            log(app, &format!("обновление: {why}"));
            *app.state::<App>().updating.lock().unwrap() = false;
            show_splash(app, &format!("Не удалось обновить: {why}"), true);
        };
        let update = match app.updater() {
            Ok(updater) => updater.check().await,
            Err(e) => Err(e),
        };
        let update = match update {
            Ok(Some(u)) => u,
            Ok(None) => {
                *app.state::<App>().updating.lock().unwrap() = false;
                remember_update(&app, None);
                send_enter(&app);
                return;
            }
            Err(e) => return fail(&app, e.to_string()),
        };
        let mut done: u64 = 0;
        let mut shown = 0;
        let progress_app = app.clone();
        let bytes = match update
            .download(
                move |chunk, total| {
                    done += chunk as u64;
                    if let Some(total) = total.filter(|t| *t > 0) {
                        let pct = (done * 100 / total) as i32;
                        if pct >= shown + 5 {
                            shown = pct;
                            set_status(
                                &progress_app,
                                &format!("Скачиваем обновление… {pct}%"),
                                false,
                            );
                        }
                    }
                },
                || {},
            )
            .await
        {
            Ok(b) => b,
            Err(e) => {
                send_enter(&app);
                return fail(&app, e.to_string());
            }
        };
        set_status(&app, "Устанавливаем обновление…", false);
        // Файлы Java и сервера будут заменены — сервер должен остановиться до установки.
        stop_server(&app);
        match update.install(bytes) {
            Ok(()) => {
                log(&app, &format!("обновлено до {}", update.version));
                app.restart();
            }
            Err(e) => {
                app.state::<App>().server.lock().unwrap().quitting = false;
                start_server(&app);
                fail(&app, e.to_string());
            }
        }
    });
}

/// Вернуть окно на сайт (новая ссылка входа), если обновление не понадобилось.
fn send_enter(app: &AppHandle) {
    let st = app.state::<App>();
    let mut server = st.server.lock().unwrap();
    send(&mut server, "enter");
}

/// Остановить сервер и дождаться (до 15 секунд), не закрывая приложение.
fn stop_server(app: &AppHandle) {
    let child = {
        let st = app.state::<App>();
        let mut server = st.server.lock().unwrap();
        server.quitting = true;
        send(&mut server, "quit");
        server.stdin = None;
        server.child.clone()
    };
    if let Some(child) = child {
        let start = Instant::now();
        loop {
            let mut c = child.lock().unwrap();
            if matches!(c.try_wait(), Ok(Some(_))) {
                break;
            }
            if start.elapsed() > Duration::from_secs(15) {
                let _ = c.kill();
                let _ = c.wait();
                break;
            }
            drop(c);
            thread::sleep(Duration::from_millis(200));
        }
    }
}

/// Пока включено, компьютер не уходит в сон — сайт остаётся доступным группе.
fn set_awake(app: &AppHandle, on: bool) {
    let st = app.state::<App>();
    let mut guard = st.awake.lock().unwrap();
    *guard = if on {
        keepawake::Builder::default()
            .idle(true)
            .reason("groupbase: сервер группы работает")
            .app_name("groupbase")
            .app_reverse_domain("app.groupbase")
            .create()
            .ok()
    } else {
        None
    };
}

// ---------- настройки оболочки ----------

fn prefs_path(app: &AppHandle) -> PathBuf {
    app.state::<App>().data.join("desktop-shell.json")
}

fn read_pref(app: &AppHandle, key: &str) -> bool {
    fs::read_to_string(prefs_path(app))
        .ok()
        .and_then(|s| serde_json::from_str::<Value>(&s).ok())
        .and_then(|v| v.get(key).and_then(Value::as_bool))
        .unwrap_or(false)
}

fn write_pref(app: &AppHandle, key: &str, value: bool) {
    let path = prefs_path(app);
    let mut v = fs::read_to_string(&path)
        .ok()
        .and_then(|s| serde_json::from_str::<Value>(&s).ok())
        .unwrap_or_else(|| serde_json::json!({}));
    v[key] = Value::Bool(value);
    let _ = fs::write(path, v.to_string());
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    #[cfg(windows)]
    fn verbatim_windows_paths_are_simplified() {
        assert_eq!(
            plain(Path::new(r"\\?\C:\Users\Омар\AppData\Local\groupbase")),
            PathBuf::from(r"C:\Users\Омар\AppData\Local\groupbase")
        );
    }

    #[test]
    fn ordinary_paths_stay() {
        let p = std::env::temp_dir().join("groupbase");
        assert_eq!(plain(&p), p);
    }
}
