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

/// Что показывает заставка: ход запуска или ошибка с кодом и кнопкой «Перезапустить».
/// Коды и что с ними делать — в docs/desktop.md («Коды ошибок») и в ui/splash.js.
#[derive(Clone, Serialize, Default)]
struct Status {
    text: String,
    error: bool,
    code: Option<String>,
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
    /// Версия, про которую уже спросили «Обновить сейчас?» — второй раз за запуск не спрашиваем.
    offered: Mutex<Option<String>>,
    updating: Mutex<bool>,
    /// Масштаб окна под текущий монитор (monitor_zoom).
    zoom: Mutex<f64>,
    data: PathBuf,
}

/// С 0.9.6 пакет на Mac называется Campus (tauri.macos.conf.json). Обновление ставит новую версию
/// по старому пути – groupbase.app, поэтому при запуске, ещё до сервера, переименовываем пакет и
/// открываем его заново; запись автозапуска переносим на новое имя и путь. Не вышло (нет прав,
/// Campus.app уже есть) – просто работаем дальше со старым именем.
#[cfg(target_os = "macos")]
fn rename_mac_bundle() {
    let Ok(exe) = std::env::current_exe() else {
        return;
    };
    // …/groupbase.app/Contents/MacOS/groupbase-desktop
    let Some(bundle) = exe.ancestors().nth(3).map(Path::to_path_buf) else {
        return;
    };
    if bundle.file_name() != Some(std::ffi::OsStr::new("groupbase.app")) {
        return;
    }
    let to = bundle.with_file_name("Campus.app");
    if to.exists() || std::fs::rename(&bundle, &to).is_err() {
        return;
    }
    let args: Vec<String> = std::env::args().skip(1).collect();
    let started = std::process::Command::new("open")
        .arg("-n")
        .arg(&to)
        .arg("--args")
        .args(&args)
        .status()
        .is_ok_and(|s| s.success());
    if !started {
        // Не открылось – возвращаем имя: ресурсы (Java, сервер) ищутся по пути этого запуска.
        let _ = std::fs::rename(&to, &bundle);
        return;
    }
    if let Some(home) = std::env::var_os("HOME") {
        let dir = PathBuf::from(home).join("Library/LaunchAgents");
        let old = dir.join("groupbase.plist");
        if let Ok(text) = std::fs::read_to_string(&old) {
            let text = text
                .replace(&*bundle.to_string_lossy(), &to.to_string_lossy())
                .replace("<string>groupbase</string>", "<string>Campus</string>");
            if std::fs::write(dir.join("Campus.plist"), text).is_ok() {
                let _ = std::fs::remove_file(&old);
            }
        }
    }
    std::process::exit(0);
}

fn main() {
    #[cfg(target_os = "macos")]
    rename_mac_bundle();
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
            // GROUPBASE_DATA — другой каталог данных (разработка, тесты, несколько групп); иначе —
            // папка, которую выбрал хост («Управление → Сервер → Состояние»), иначе — стандартная.
            let base = plain(&app.path().app_data_dir()?);
            let (data, missing) = match std::env::var_os("GROUPBASE_DATA") {
                Some(dir) => (plain(&PathBuf::from(dir)), false),
                None => match chosen_data(&base) {
                    Some(dir) if dir.is_dir() => (dir, false),
                    Some(dir) => (dir, true),
                    None => (base.clone(), false),
                },
            };
            if missing {
                // Диск с данными не подключён: пустой сайт на его месте только запутает группу.
                let gone = data.clone();
                app.manage(App {
                    server: Mutex::new(Server {
                        quitting: true,
                        ..Server::default()
                    }),
                    awake: Mutex::new(None),
                    copy_item: Mutex::new(None),
                    update_item: Mutex::new(None),
                    update: Mutex::new(None),
                    offered: Mutex::new(None),
                    updating: Mutex::new(false),
                    zoom: Mutex::new(1.0),
                    data: base,
                });
                let handle = app.handle().clone();
                app.dialog()
                    .message(format!(
                        "Папка с данными сайта не найдена:\n{}\n\nПодключите диск с ней и запустите \
                         groupbase снова.",
                        gone.display()
                    ))
                    .title("Campus")
                    .show(move |_| handle.exit(1));
                return Ok(());
            }
            fs::create_dir_all(data.join("logs"))?;
            app.manage(App {
                server: Mutex::new(Server::default()),
                awake: Mutex::new(None),
                copy_item: Mutex::new(None),
                update_item: Mutex::new(None),
                update: Mutex::new(None),
                offered: Mutex::new(None),
                updating: Mutex::new(false),
                zoom: Mutex::new(1.0),
                data,
            });
            create_window(app.handle(), !hidden)?;
            build_tray(app.handle())?;
            // Значок, выбранный в «Профиль → Приложение» (сервер присылает событие icon).
            let icon = read_pref_text(app.handle(), "icon");
            if !icon.is_empty() {
                apply_icon(app.handle(), &icon);
            }
            if read_pref(app.handle(), "keepAwake") {
                set_awake(app.handle(), true);
            }
            start_server(app.handle());
            schedule_update_checks(app.handle().clone());
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("не удалось запустить Campus");

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
        .title("Campus")
        .inner_size(1180.0, 800.0)
        .min_inner_size(380.0, 560.0)
        .visible(visible)
        // Файлы, перетащенные в окно, должна получить страница (зона «Выберите файл или
        // перетащите сюда»), а не оболочка: иначе вложение к заданию так не прикрепить.
        .disable_drag_drop_handler()
        // Внутри окна – только сайт группы и заставка; остальные ссылки – в браузере.
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
        // Скачанное (копии, архивы, файлы) – в «Загрузки», затем показываем файл.
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
                // После перенаправлений реальный адрес – у самого окна.
                let url = webview.url().unwrap_or_else(|_| payload.url().clone());
                log(
                    webview.app_handle(),
                    &format!("страница: {}", without_query(&url)),
                );
            }
        })
        .build()?;
    // Окно и масштаб – под монитор: на большом мониторе окно больше и всё крупнее.
    if let Some(z) = window_zoom(&window) {
        let _ = window.set_zoom(z);
        if let Ok(Some(m)) = window.current_monitor() {
            let area = m.size().to_logical::<f64>(m.scale_factor());
            let _ = window.set_size(tauri::LogicalSize::new(
                (1180.0 * z).min(area.width * 0.92),
                (800.0 * z).min(area.height * 0.88),
            ));
            let _ = window.center();
        }
        *app.state::<App>().zoom.lock().unwrap() = z;
    }
    let w = window.clone();
    window.on_window_event(move |event| match event {
        WindowEvent::CloseRequested { api, .. } => {
            // Закрыть окно ≠ выключить сайт: прячем окно, сервер работает дальше.
            api.prevent_close();
            let _ = w.hide();
        }
        // Перетащили окно на другой монитор – масштаб под него.
        WindowEvent::Moved(_) | WindowEvent::ScaleFactorChanged { .. } => {
            if let Some(z) = window_zoom(&w) {
                let st = w.app_handle().state::<App>();
                let mut last = st.zoom.lock().unwrap();
                if (*last - z).abs() > 0.01 {
                    *last = z;
                    let _ = w.set_zoom(z);
                }
            }
        }
        _ => {}
    });
    Ok(())
}

/// Масштаб интерфейса под монитор (просьба владельца – «везде выглядело одинаково»): сайт
/// рассчитан на ширину около 1440 точек; на мониторе 1920 всё в 1,35 раза крупнее, на 2560 – в 1,5,
/// на тесном ноутбуке – чуть мельче. Шаг – 5 %.
fn monitor_zoom(window: &tauri::WebviewWindow) -> Option<f64> {
    let m = window.current_monitor().ok().flatten()?;
    let width = m.size().to_logical::<f64>(m.scale_factor()).width;
    Some(zoom_for(width))
}

/// Масштаб окна: свой из «Профиль → Приложение → Масштаб окна» или «Авто» – под монитор.
fn window_zoom(window: &tauri::WebviewWindow) -> Option<f64> {
    let manual = read_pref_number(window.app_handle(), "zoom");
    if manual >= 50.0 {
        return Some(manual / 100.0);
    }
    monitor_zoom(window)
}

/// «Авто» (0.8.1 – мягче, владельцу на 1920 было слишком крупно): 1920 – 110 %, 2560 и шире –
/// 125 %, ноутбуки – 85 %. Шаг 5 %.
fn zoom_for(width: f64) -> f64 {
    ((width / 1760.0).clamp(0.85, 1.25) * 20.0).round() / 20.0
}

/// Выбрали масштаб в окне: применить сразу и запомнить (0 – «Авто»).
fn set_window_zoom(app: &AppHandle, value: f64) {
    write_pref_value(app, "zoom", serde_json::json!(value));
    if let Some(w) = app.get_webview_window(MAIN) {
        if let Some(z) = window_zoom(&w) {
            *app.state::<App>().zoom.lock().unwrap() = z;
            let _ = w.set_zoom(z);
        }
    }
}

/// Адрес без параметров: в ссылке входа – одноразовый токен, в журнал он не попадает.
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

/// Файл с таким именем уже есть – добавляем « (2)», « (3)»…
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
    emit_status(
        app,
        Status {
            text: text.to_string(),
            error,
            code: None,
        },
    );
}

/// Ошибка с кодом: заставка покажет причину, код и что делать.
fn set_error(app: &AppHandle, code: &str, text: &str) {
    emit_status(
        app,
        Status {
            text: text.to_string(),
            error: true,
            code: Some(code.to_string()),
        },
    );
}

fn emit_status(app: &AppHandle, status: Status) {
    app.state::<App>().server.lock().unwrap().status = status.clone();
    let _ = app.emit_to(MAIN, "status", status);
}

/// Вернуть окно на заставку (перезапуск сервера).
fn show_splash(app: &AppHandle, text: &str, error: bool) {
    set_status(app, text, error);
    to_splash(app);
}

/// Вернуть окно на заставку с ошибкой и её кодом.
fn show_error(app: &AppHandle, code: &str, text: &str) {
    set_error(app, code, text);
    to_splash(app);
}

fn to_splash(app: &AppHandle) {
    if let Some(w) = app.get_webview_window(MAIN) {
        if !w.url().map(|u| is_local_page(&u)).unwrap_or(false) {
            let _ = w.navigate(splash_url());
        }
    }
}

/// Причина по хвосту журнала сервера – там, где сервер не успел сказать её сам.
fn classify(tail: &str) -> Option<(&'static str, &'static str)> {
    if tail.contains("No space left") || tail.contains("SQLITE_FULL") {
        Some((
            "GB-207",
            "На диске закончилось место. Освободите его и перезапустите приложение.",
        ))
    } else if tail.contains("database disk image is malformed")
        || tail.contains("SQLITE_CORRUPT")
        || tail.contains("SQLITE_NOTADB")
    {
        Some((
            "GB-206",
            "Не открывается база данных. Восстановите данные из резервной копии.",
        ))
    } else if tail.contains("OutOfMemoryError") {
        Some((
            "GB-209",
            "Серверу не хватило памяти. Закройте тяжёлые программы и перезапустите.",
        ))
    } else {
        None
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
            "Не найдены файлы приложения (встроенная Java или сервер) в {}. Переустановите \
             Campus поверх – данные сохранятся.",
            res.display()
        ));
    }
    // Приложение скачано из интернета и уже разрешено пользователем, но встроенная Java несёт
    // свой флаг карантина – снимаем его, чтобы macOS не спрашивала про неё отдельно.
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
        Err(e) => return show_error(app, "GB-204", &e),
    };
    let java_log = OpenOptions::new()
        .create(true)
        .append(true)
        .open(data.join("logs").join("java.log"))
        .ok();
    let mut cmd = Command::new(&java);
    // Java на Windows читает аргументы в кодировке системы: путь с кириллицей в имени пользователя
    // может не дойти. Поэтому jar – относительно рабочего каталога, а каталог данных – через
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
        Err(e) => return show_error(app, "GB-205", &format!("Не удалось запустить сервер: {e}")),
    };
    log(app, &format!("сервер запущен (pid {})", child.id()));
    let stdout = child.stdout.take();
    let child = Arc::new(Mutex::new(child));
    // Перезапущенный сервер не знает, что новая версия уже найдена, – иначе кнопка «Обновить»
    // пропала бы до следующей проверки через 6 часов.
    let known = st.update.lock().unwrap().clone();
    {
        let mut server = st.server.lock().unwrap();
        server.stdin = child.lock().unwrap().stdin.take();
        server.child = Some(child.clone());
        server.quitting = false;
        if let Some(version) = known {
            send(&mut server, &format!("update-available {version}"));
        }
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

/// Ждём завершения сервера: код 3 – перезапуск, иначе (если это не выход) – ошибка на заставке.
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
    // Причина – от самого сервера (он успел сказать), иначе по журналу, иначе – просто «упал».
    let (code, reason) = if last.error {
        (
            last.code.unwrap_or_else(|| "GB-200".into()),
            last.text.split("\n\n").next().unwrap_or("").to_string(),
        )
    } else if let Some((c, r)) = tail.as_deref().and_then(classify) {
        (c.to_string(), r.to_string())
    } else {
        (
            "GB-203".to_string(),
            match code {
                Some(c) => format!("Сервер группы неожиданно остановился (код выхода {c})."),
                None => "Сервер группы неожиданно остановился.".to_string(),
            },
        )
    };
    show_error(
        &app,
        &code,
        &format!(
            "{reason}{}",
            tail.map(|t| format!("\n\n{t}")).unwrap_or_default()
        ),
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
            // Сайт работает на другом компьютере хоста – сервер присылает, что написать в меню.
            let label = text("label");
            let st = app.state::<App>();
            st.server.lock().unwrap().public = if url.is_empty() {
                None
            } else {
                Some(url.clone())
            };
            if let Some(item) = st.copy_item.lock().unwrap().as_ref() {
                let _ = item.set_enabled(!url.is_empty());
                let _ = item.set_text(if !url.is_empty() {
                    format!("Скопировать ссылку: {}", short(&url))
                } else if !label.is_empty() {
                    label
                } else {
                    "Доступ для группы выключен".to_string()
                });
            };
        }
        "restart" => show_splash(app, "Перезапускаем сервер…", false),
        "status" => set_status(app, &text("message"), false),
        "error" => {
            let code = text("code");
            set_error(
                app,
                if code.is_empty() { "GB-200" } else { &code },
                &text("message"),
            );
        }
        // Выбрали значок в окне хоста – Dock на Mac, панель задач на Windows; запоминаем.
        "icon" => {
            let id = text("icon");
            if id == "dark" || icon_png(&id).is_some() {
                write_pref_value(app, "icon", Value::String(id.clone()));
                apply_icon(app, &id);
            }
        }
        // «Масштаб окна» в «Профиль → Приложение».
        "zoom" => {
            let value = v.get("value").and_then(Value::as_f64).unwrap_or(0.0);
            set_window_zoom(app, value);
        }
        // «Сменить папку…» в «Управление → Сервер → Состояние»: диалоги блокирующие – не здесь.
        "choose-data" => {
            let app = app.clone();
            thread::spawn(move || choose_data(&app));
        }
        // Другой компьютер просит подключиться к сайту: спрашиваем здесь, даже если окно закрыто.
        "peer-request" => {
            let app = app.clone();
            let id = text("id");
            let name = text("name");
            thread::spawn(move || ask_peer(&app, &id, &name));
        }
        // «Обновить» в «Настройки → Сервер → Состояние».
        "update" => install_update(app),
        // «Состояние» открыли раньше, чем оболочка проверила обновления сама.
        "check-update" => check_update(app, false),
        _ => {}
    }
}

fn short(url: &str) -> String {
    url.trim_start_matches("https://")
        .trim_start_matches("http://")
        .to_string()
}

/// «Разрешить подключение?» – ответ уходит серверу командой `peer-allow` / `peer-deny`.
fn ask_peer(app: &AppHandle, id: &str, name: &str) {
    if id.is_empty() || !id.chars().all(|c| c.is_ascii_alphanumeric()) {
        return;
    }
    let who = if name.is_empty() {
        "Другой компьютер"
    } else {
        name
    };
    let yes = app
        .dialog()
        .message(format!(
            "«{who}» хочет подключиться к сайту и работать с ним вместе с этим компьютером: \
             получит все данные сайта и будет их обновлять.\n\nРазрешайте, только если это ваш компьютер."
        ))
        .title("Подключить компьютер?")
        .buttons(MessageDialogButtons::OkCancelCustom(
            "Разрешить".to_string(),
            "Отклонить".to_string(),
        ))
        .blocking_show();
    let st = app.state::<App>();
    let mut server = st.server.lock().unwrap();
    send(
        &mut server,
        &format!("{} {id}", if yes { "peer-allow" } else { "peer-deny" }),
    );
}

fn send(server: &mut Server, command: &str) {
    if let Some(stdin) = server.stdin.as_mut() {
        let _ = writeln!(stdin, "{command}");
        let _ = stdin.flush();
    }
}

/// Выход: сервер завершается сам (закрывает базу и туннель), через 15 секунд – принудительно.
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
    let open = MenuItem::with_id(app, "open", "Открыть Campus", true, None::<&str>)?;
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
        .tooltip("Campus – сервер группы")
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
            // Windows: щелчок по значку открывает окно (меню – правой кнопкой).
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

/// interactive – пользователь сам нажал «Проверить»: ему ответят и «обновлений нет».
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
                // Сами спрашиваем один раз за запуск: хосту не нужно искать кнопку в настройках.
                let first_time = {
                    let st = app.state::<App>();
                    let mut offered = st.offered.lock().unwrap();
                    let new = offered.as_deref() != Some(version.as_str());
                    *offered = Some(version.clone());
                    new
                };
                let busy = *app.state::<App>().updating.lock().unwrap();
                if (interactive || first_time) && !busy {
                    ask_update(&app, &version);
                }
            }
            Ok(None) => {
                remember_update(&app, None);
                if interactive {
                    info(&app, "У вас последняя версия Campus.");
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
    app.dialog().message(text).title("Campus").show(|_| {});
}

fn ask_update(app: &AppHandle, version: &str) {
    let app2 = app.clone();
    app.dialog()
        .message(format!(
            "Вышла новая версия groupbase {version}. Обновить сейчас?\n\nСайт группы будет недоступен \
             около минуты, данные сохранятся."
        ))
        .title("Обновление Campus")
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
        // Не вышло – окно возвращается на сайт (он работает на прежней версии), а причина – в окне
        // сообщения: заставка с ошибкой здесь не нужна, сервер ведь не падал.
        let fail = |app: &AppHandle, why: String| {
            log(app, &format!("обновление: {why}"));
            *app.state::<App>().updating.lock().unwrap() = false;
            set_status(app, "Готово", false);
            send_enter(app);
            info(
                app,
                &format!(
                    "Не удалось обновить groupbase: {why}\n\nСайт работает на прежней версии. \
                     Проверьте интернет и нажмите «Обновить» ещё раз."
                ),
            );
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
                set_status(&app, "Готово", false);
                send_enter(&app);
                info(&app, "У вас уже последняя версия Campus.");
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
            Err(e) => return fail(&app, e.to_string()),
        };
        set_status(&app, "Устанавливаем обновление…", false);
        // Файлы Java и сервера будут заменены – сервер должен остановиться до установки.
        stop_server(&app);
        match update.install(bytes) {
            Ok(()) => {
                log(&app, &format!("обновлено до {}", update.version));
                app.restart();
            }
            Err(e) => {
                // Сервер уже остановлен: запускаем прежнюю версию, окно вернётся на сайт по «ready».
                app.state::<App>().server.lock().unwrap().quitting = false;
                log(&app, &format!("обновление: {e}"));
                *app.state::<App>().updating.lock().unwrap() = false;
                start_server(&app);
                info(
                    &app,
                    &format!(
                        "Не удалось установить обновление: {e}\n\nСайт снова работает на прежней \
                         версии."
                    ),
                );
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

/// Остановить сервер ради обновления и дождаться (до 15 секунд), не закрывая приложение.
fn stop_server(app: &AppHandle) {
    let child = {
        let st = app.state::<App>();
        let mut server = st.server.lock().unwrap();
        server.quitting = true;
        // Сервер скоро вернётся: другой компьютер хоста не должен забирать сайт на эти полминуты.
        send(&mut server, "updating");
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

// ---------- папка данных ----------

/// Куда хост перенёс данные: `location.json` в стандартном каталоге приложения.
fn chosen_data(base: &Path) -> Option<PathBuf> {
    fs::read_to_string(base.join("location.json"))
        .ok()
        .and_then(|s| serde_json::from_str::<Value>(&s).ok())
        .and_then(|v| v.get("data").and_then(Value::as_str).map(PathBuf::from))
        .filter(|p| !p.as_os_str().is_empty())
        .map(|p| plain(&p))
}

/// Папка для данных в выбранной: сама она, если пустая или в ней уже данные groupbase, иначе –
/// подпапка «groupbase» (выбрали «Документы» – не мусорим в них).
fn data_target(picked: &Path) -> PathBuf {
    let empty = fs::read_dir(picked)
        .map(|mut d| d.next().is_none())
        .unwrap_or(true);
    if empty || picked.join("groupbase.db").is_file() {
        picked.to_path_buf()
    } else {
        picked.join("groupbase")
    }
}

fn same_dir(a: &Path, b: &Path) -> bool {
    match (fs::canonicalize(a), fs::canonicalize(b)) {
        (Ok(x), Ok(y)) => x == y,
        _ => a == b,
    }
}

/// Копия каталога целиком; `location.json` (указатель на папку) не копируется.
fn copy_dir(from: &Path, to: &Path) -> std::io::Result<u64> {
    fs::create_dir_all(to)?;
    let mut bytes = 0;
    for entry in fs::read_dir(from)? {
        let entry = entry?;
        let name = entry.file_name();
        if name == "location.json" {
            continue;
        }
        let (src, dst) = (entry.path(), to.join(&name));
        if entry.file_type()?.is_dir() {
            bytes += copy_dir(&src, &dst)?;
        } else {
            bytes += fs::copy(&src, &dst)?;
        }
    }
    Ok(bytes)
}

/// Все файлы на месте и того же размера – тогда старую папку можно убрать.
fn same_files(from: &Path, to: &Path) -> bool {
    let Ok(entries) = fs::read_dir(from) else {
        return false;
    };
    entries.flatten().all(|e| {
        if e.file_name() == "location.json" {
            return true;
        }
        let dst = to.join(e.file_name());
        match e.file_type() {
            Ok(t) if t.is_dir() => same_files(&e.path(), &dst),
            Ok(_) => match (e.metadata(), fs::metadata(&dst)) {
                (Ok(a), Ok(b)) => a.len() == b.len(),
                _ => false,
            },
            Err(_) => false,
        }
    })
}

fn write_location(base: &Path, data: &Path) -> std::io::Result<()> {
    fs::create_dir_all(base)?;
    let file = base.join("location.json");
    if same_dir(base, data) {
        return match fs::remove_file(&file) {
            Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(e),
            _ => Ok(()),
        };
    }
    let tmp = base.join("location.json.part");
    fs::write(&tmp, serde_json::json!({ "data": data }).to_string())?;
    fs::rename(&tmp, &file)
}

/// Убрать прежнюю папку данных; стандартный каталог приложения остаётся (в нём указатель).
fn remove_old(old: &Path, base: &Path) {
    if same_dir(old, base) {
        if let Ok(entries) = fs::read_dir(old) {
            for e in entries.flatten() {
                if e.file_name() == "location.json" {
                    continue;
                }
                let _ = if e.path().is_dir() {
                    fs::remove_dir_all(e.path())
                } else {
                    fs::remove_file(e.path())
                };
            }
        }
    } else {
        let _ = fs::remove_dir_all(old);
    }
}

/// Выбрать другую папку для данных сайта: скопировать, проверить, переключиться и перезапуститься.
/// В папке уже есть данные groupbase – переключиться на них (текущие остаются на месте).
fn choose_data(app: &AppHandle) {
    let current = app.state::<App>().data.clone();
    let Ok(base) = app.path().app_data_dir().map(|p| plain(&p)) else {
        return;
    };
    let Some(picked) = app
        .dialog()
        .file()
        .set_title("Где хранить данные сайта")
        .blocking_pick_folder()
        .and_then(|p| p.into_path().ok())
    else {
        return;
    };
    let target = data_target(&plain(&picked));
    if same_dir(&target, &current) {
        return;
    }
    if target.starts_with(&current) {
        info(
            app,
            "Нельзя перенести данные внутрь их же папки – выберите другую.",
        );
        return;
    }
    let existing = target.join("groupbase.db").is_file();
    let (text, ok) = if existing {
        (
            format!(
                "В папке {} уже есть данные groupbase. Открыть сайт с ними?\n\nТекущие данные \
                 останутся в {}.",
                target.display(),
                current.display()
            ),
            "Открыть",
        )
    } else {
        (
            format!(
                "Перенести данные сайта в {}?\n\nСайт остановится примерно на минуту: данные \
                 скопируются, проверятся, и старая папка удалится.",
                target.display()
            ),
            "Перенести",
        )
    };
    let yes = app
        .dialog()
        .message(text)
        .title("Папка с данными")
        .buttons(MessageDialogButtons::OkCancelCustom(
            ok.to_string(),
            "Отмена".to_string(),
        ))
        .blocking_show();
    if !yes {
        return;
    }
    log(app, &format!("перенос данных: {}", target.display()));
    show_splash(app, "Переносим данные…", false);
    stop_server(app);
    let failed = |why: String| {
        log(app, &format!("перенос данных не удался: {why}"));
        info(
            app,
            &format!("Не удалось перенести данные: {why}\n\nСайт работает со старой папкой."),
        );
        start_server(app);
    };
    if !existing {
        if let Err(e) = copy_dir(&current, &target) {
            let _ = fs::remove_dir_all(&target);
            return failed(e.to_string());
        }
        if !same_files(&current, &target) {
            let _ = fs::remove_dir_all(&target);
            return failed("копия не совпала с оригиналом".to_string());
        }
    }
    if let Err(e) = write_location(&base, &target) {
        return failed(e.to_string());
    }
    if !existing {
        remove_old(&current, &base);
    }
    app.restart();
}

/// Пока включено, компьютер не уходит в сон – сайт остаётся доступным группе.
fn set_awake(app: &AppHandle, on: bool) {
    let st = app.state::<App>();
    let mut guard = st.awake.lock().unwrap();
    *guard = if on {
        keepawake::Builder::default()
            .idle(true)
            .reason("Campus: сервер группы работает")
            .app_name("Campus")
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

fn read_pref_number(app: &AppHandle, key: &str) -> f64 {
    fs::read_to_string(prefs_path(app))
        .ok()
        .and_then(|s| serde_json::from_str::<Value>(&s).ok())
        .and_then(|v| v.get(key).and_then(Value::as_f64))
        .unwrap_or(0.0)
}

fn read_pref_text(app: &AppHandle, key: &str) -> String {
    fs::read_to_string(prefs_path(app))
        .ok()
        .and_then(|s| serde_json::from_str::<Value>(&s).ok())
        .and_then(|v| v.get(key).and_then(Value::as_str).map(str::to_string))
        .unwrap_or_default()
}

fn write_pref(app: &AppHandle, key: &str, value: bool) {
    write_pref_value(app, key, Value::Bool(value));
}

fn write_pref_value(app: &AppHandle, key: &str, value: Value) {
    let path = prefs_path(app);
    let mut v = fs::read_to_string(&path)
        .ok()
        .and_then(|s| serde_json::from_str::<Value>(&s).ok())
        .unwrap_or_else(|| serde_json::json!({}));
    v[key] = value;
    let _ = fs::write(path, v.to_string());
}

/// Картинки значков на выбор (web/static/icons, `java scripts/Icons.java variants`). «dark» – значок
/// приложения по умолчанию: для него – None, ставится родной.
fn icon_png(id: &str) -> Option<&'static [u8]> {
    Some(match id {
        "light" => include_bytes!("../../../web/static/icons/icon-512.png"),
        "ocean" => include_bytes!("../../../web/static/icons/v/ocean-512.png"),
        "forest" => include_bytes!("../../../web/static/icons/v/forest-512.png"),
        "sunset" => include_bytes!("../../../web/static/icons/v/sunset-512.png"),
        "grape" => include_bytes!("../../../web/static/icons/v/grape-512.png"),
        _ => return None,
    })
}

fn apply_icon(app: &AppHandle, id: &str) {
    let png = icon_png(id);
    let handle = app.clone();
    let _ = app.run_on_main_thread(move || show_icon(&handle, png));
}

/// Mac: значок в Dock, пока приложение запущено (после выхода Dock показывает значок из пакета).
#[cfg(target_os = "macos")]
fn show_icon(_app: &AppHandle, png: Option<&'static [u8]>) {
    use objc2::{AllocAnyThread, MainThreadMarker};
    use objc2_app_kit::{NSApplication, NSImage};
    use objc2_foundation::NSData;
    let Some(mtm) = MainThreadMarker::new() else {
        return;
    };
    let app = NSApplication::sharedApplication(mtm);
    let image =
        png.and_then(|bytes| NSImage::initWithData(NSImage::alloc(), &NSData::with_bytes(bytes)));
    unsafe { app.setApplicationIconImage(image.as_deref()) };
}

/// Windows и Linux: значок окна – он же на панели задач.
#[cfg(not(target_os = "macos"))]
fn show_icon(app: &AppHandle, png: Option<&'static [u8]>) {
    let icon = match png {
        Some(bytes) => tauri::image::Image::from_bytes(bytes).ok(),
        None => app.default_window_icon().cloned(),
    };
    if let (Some(w), Some(icon)) = (app.get_webview_window(MAIN), icon) {
        let _ = w.set_icon(icon);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn data_goes_to_a_subfolder_of_a_busy_folder() {
        let root = std::env::temp_dir().join(format!("gb-shell-test-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        let empty = root.join("empty");
        let busy = root.join("busy");
        let site = root.join("site");
        fs::create_dir_all(&empty).unwrap();
        fs::create_dir_all(&busy).unwrap();
        fs::create_dir_all(&site).unwrap();
        fs::write(busy.join("notes.txt"), "x").unwrap();
        fs::write(site.join("groupbase.db"), "x").unwrap();
        assert_eq!(data_target(&empty), empty);
        assert_eq!(data_target(&busy), busy.join("groupbase"));
        assert_eq!(data_target(&site), site);

        // Копия целиком, указатель не копируется, проверка видит расхождение.
        let from = root.join("from");
        fs::create_dir_all(from.join("files")).unwrap();
        fs::write(from.join("groupbase.db"), "db").unwrap();
        fs::write(from.join("files").join("a"), "abc").unwrap();
        fs::write(from.join("location.json"), "{}").unwrap();
        let to = root.join("to");
        assert_eq!(copy_dir(&from, &to).unwrap(), 5);
        assert!(!to.join("location.json").exists());
        assert!(same_files(&from, &to));
        fs::write(to.join("files").join("a"), "ab").unwrap();
        assert!(!same_files(&from, &to));

        // Указатель: в стандартный каталог – не нужен, в другой – записан и читается.
        let base = root.join("base");
        write_location(&base, &to).unwrap();
        assert_eq!(chosen_data(&base), Some(plain(&to)));
        write_location(&base, &base).unwrap();
        assert_eq!(chosen_data(&base), None);
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn zoom_follows_monitor_width() {
        assert_eq!(zoom_for(1440.0), 0.85);
        assert_eq!(zoom_for(1760.0), 1.0);
        assert_eq!(zoom_for(1920.0), 1.1);
        assert_eq!(zoom_for(2560.0), 1.25);
        assert_eq!(zoom_for(3840.0), 1.25);
        assert_eq!(zoom_for(1280.0), 0.85);
    }

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
