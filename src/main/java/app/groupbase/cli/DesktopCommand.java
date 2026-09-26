package app.groupbase.cli;

import app.groupbase.desktop.DesktopBridge;
import app.groupbase.desktop.DesktopConfig;
import app.groupbase.hosts.HostSwitch;
import app.groupbase.store.DataDirLock;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Сервер внутри приложения для компьютера. Оболочка (desktop/) запускает эту команду дочерним
 * процессом и общается через стандартные потоки:
 *
 * <ul>
 *   <li>stdout — события {@code @gb {"event": ...}}: ready (адрес и ссылка входа для окна), enter,
 *       public-url, restart, error;
 *   <li>stdin — команды построчно: {@code enter} (новая ссылка входа), {@code updating} (сейчас
 *       остановят ради обновления), {@code quit}. Конец stdin — оболочка закрылась: сервер тоже
 *       завершается.
 * </ul>
 *
 * Код выхода {@value #RESTART} — «перезапусти меня» (после восстановления из копии или смены сети).
 */
@Command(
    name = "desktop",
    hidden = true,
    description = "Сервер для приложения хоста (запускается самим приложением).",
    mixinStandardHelpOptions = true)
public class DesktopCommand implements Callable<Integer> {

  public static final int RESTART = 3;

  /** Оболочка передаёт каталог так: переменные окружения на Windows всегда в Юникоде. */
  static final String ENV_DATA = "GROUPBASE_DESKTOP_DATA";

  @Option(
      names = "--data",
      description = "Каталог данных (по умолчанию — из переменной " + ENV_DATA + ").")
  Path data;

  @Override
  public Integer call() throws Exception {
    DesktopBridge.Out out = DesktopBridge.Out.stdout();
    if (data == null) {
      String env = System.getenv(ENV_DATA);
      if (env == null || env.isBlank()) {
        out.event("error", Map.of("message", "Не задан каталог данных", "code", "GB-200"));
        return 2;
      }
      data = Path.of(env);
    }
    ConfigurableApplicationContext ctx;
    int port;
    try {
      Files.createDirectories(data);
      // Прежний сервер (перезапуск, быстрый повторный запуск) может ещё завершаться — ждём его.
      for (int i = 0; i < 40 && DataDirLock.held(data); i++) {
        if (i == 0) {
          out.event("status", Map.of("message", "Ждём, пока завершится прежний запуск…"));
        }
        Thread.sleep(500);
      }
      // Сайт на нескольких компьютерах: закрыли на другом — берём его данные до старта сервера.
      int sitePort = HostSwitch.prepare(data, msg -> out.event("status", Map.of("message", msg)));
      DesktopConfig net = DesktopConfig.load(data);
      if (sitePort > 0) {
        // Адрес в CloudPub ведёт на порт прежнего компьютера — по возможности тот же.
        net.prefer(sitePort);
      }
      port = net.choosePort();
      Map<String, Object> props = new LinkedHashMap<>();
      props.put("groupbase.data-dir", data.toAbsolutePath().toString());
      props.put("groupbase.http.port", String.valueOf(port));
      props.put("groupbase.http.address", net.bindAddress());
      props.put("groupbase.desktop.enabled", "true");
      ctx = AppContext.desktop(data, props, msg -> out.event("status", Map.of("message", msg)));
    } catch (Exception e) {
      Problem p = problem(e);
      out.event("error", Map.of("message", p.message(), "code", p.code()));
      return 1;
    }

    DesktopBridge bridge = ctx.getBean(DesktopBridge.class);
    CountDownLatch done = new CountDownLatch(1);
    bridge.onRestart(done::countDown);
    ctx.addApplicationListener((ApplicationListener<ContextClosedEvent>) e -> done.countDown());
    Thread.ofPlatform().daemon().name("desktop-stdin").start(() -> readCommands(bridge, done));

    if (bridge.restartRequested()) {
      // Перезапуск попросили ещё во время запуска (сайт взяли с другого компьютера) — окно не
      // открываем, сразу на второй круг.
      done.countDown();
    } else {
      bridge.event(
          "ready",
          Map.of(
              "url", bridge.localUrl(),
              "enter", bridge.enterUrl(),
              "version", Main.version(),
              "port", port));
    }
    done.await();
    int code = bridge.restartRequested() ? RESTART : 0;
    // Сервер не должен пережить оболочку: если остановка зависла, через 25 секунд — выход.
    Thread.ofPlatform()
        .daemon()
        .start(
            () -> {
              try {
                Thread.sleep(25_000);
              } catch (InterruptedException e) {
                return;
              }
              Runtime.getRuntime().halt(code);
            });
    try {
      if (ctx.isActive()) {
        ctx.close();
      }
    } catch (RuntimeException | LinkageError e) {
      // Остановка не удалась — выходим всё равно.
    }
    return code;
  }

  private static void readCommands(DesktopBridge bridge, CountDownLatch done) {
    try (BufferedReader in =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
      for (String line; (line = in.readLine()) != null; ) {
        String cmd = line.strip();
        if (cmd.startsWith("update-available")) {
          bridge.setAvailableUpdate(cmd.substring("update-available".length()));
          continue;
        }
        if (cmd.equals("updating")) {
          // Следом придёт quit: сервер остановят ради обновления, и он скоро вернётся.
          bridge.markUpdating();
          continue;
        }
        switch (cmd) {
          case "enter" -> bridge.event("enter", Map.of("url", bridge.enterUrl()));
          case "quit" -> {
            done.countDown();
            return;
          }
          default -> {
            // Неизвестные команды пропускаем: оболочка может быть новее сервера.
          }
        }
      }
    } catch (IOException e) {
      // stdin закрыт — как и конец потока, значит оболочки больше нет.
    }
    done.countDown();
  }

  /**
   * Почему сервер не запустился — понятной фразой и кодом. Коды и что делать с каждым описаны в
   * docs/desktop.md («Коды ошибок»); коды 204, 205 и 203 выдаёт сама оболочка.
   */
  record Problem(String code, String message) {}

  static Problem problem(Throwable e) {
    String last = null;
    for (Throwable t = e; t != null; t = t.getCause()) {
      String m = t.getMessage() == null ? "" : t.getMessage();
      if (t instanceof java.net.BindException) {
        return new Problem("GB-201", "Порт уже занят другой программой. Перезапустите приложение.");
      }
      if (m.contains("уже запущен")) {
        return new Problem("GB-202", "groupbase уже запущен на этом компьютере.");
      }
      if (m.contains("No space left") || m.contains("SQLITE_FULL")) {
        return new Problem(
            "GB-207", "На диске закончилось место. Освободите его и перезапустите приложение.");
      }
      if (m.contains("SQLITE_CORRUPT")
          || m.contains("SQLITE_NOTADB")
          || m.contains("database disk image is malformed")
          || t instanceof org.flywaydb.core.api.FlywayException) {
        return new Problem(
            "GB-206", "Не открывается база данных. Восстановите данные из резервной копии.");
      }
      if (m.startsWith(AppContext.RESTORE_FAILED)) {
        return new Problem("GB-208", m);
      }
      if (!m.isBlank()) {
        last = m;
      }
    }
    return new Problem("GB-200", last == null ? String.valueOf(e) : last);
  }
}
