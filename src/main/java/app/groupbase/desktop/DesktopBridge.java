package app.groupbase.desktop;

import app.groupbase.auth.Tokens;
import app.groupbase.config.GroupbaseProperties;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Связь с оболочкой приложения для компьютера. Оболочка запускает сервер как дочерний процесс и
 * читает события из stdout — строки {@code @gb {...}}; команды приходят в stdin (см. {@link
 * app.groupbase.cli.DesktopCommand}). Вне приложения (обычный {@code serve}) всё здесь ничего не
 * делает.
 */
@Component
public class DesktopBridge {

  public static final String PREFIX = "@gb ";
  private static final Duration ENTER_TTL = Duration.ofMinutes(2);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Вывод событий в stdout; нужен и до старта Spring (ошибки запуска). */
  public static final class Out {
    private static final Out STDOUT =
        new Out(
            new PrintStream(
                new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
    private final PrintStream stream;

    private Out(PrintStream stream) {
      this.stream = stream;
    }

    public static Out stdout() {
      return STDOUT;
    }

    public void event(String name, Map<String, ?> fields) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("event", name);
      m.putAll(fields);
      String line = PREFIX + JSON.writeValueAsString(m);
      synchronized (stream) {
        stream.println(line);
      }
    }
  }

  private final boolean enabled;
  private final int port;
  private final Clock clock;
  private final Out out = Out.stdout();
  private final Map<String, Long> enterTokens = new ConcurrentHashMap<>();
  private final AtomicBoolean restart = new AtomicBoolean();

  /** Новая версия приложения, найденная оболочкой (её механизм обновлений). */
  private volatile String availableUpdate;

  private volatile Runnable onRestart = () -> {};

  public DesktopBridge(GroupbaseProperties props, Clock clock) {
    this.enabled = props.desktop().enabled();
    this.port = props.http().port();
    this.clock = clock;
  }

  public boolean enabled() {
    return enabled;
  }

  /** Адрес сервера для окна хоста. */
  public String localUrl() {
    return "http://127.0.0.1:" + port;
  }

  public void event(String name, Map<String, ?> fields) {
    if (enabled) {
      out.event(name, fields);
    }
  }

  /**
   * Одноразовая ссылка входа для окна приложения: действует две минуты, передаётся оболочке через
   * stdout и больше никуда не попадает.
   */
  public String enterUrl() {
    long now = clock.millis();
    enterTokens.values().removeIf(exp -> exp < now);
    String token = Tokens.newToken();
    enterTokens.put(token, now + ENTER_TTL.toMillis());
    return localUrl() + "/api/desktop/enter?t=" + token;
  }

  public boolean consumeEnterToken(String token) {
    if (!enabled || token == null) {
      return false;
    }
    Long exp = enterTokens.remove(token);
    return exp != null && exp >= clock.millis();
  }

  /** Перезапуск сервера оболочкой: восстановление из копии, смена сетевого режима. */
  public void requestRestart() {
    if (!enabled) {
      return;
    }
    restart.set(true);
    event("restart", Map.of());
    onRestart.run();
  }

  public String availableUpdate() {
    return availableUpdate;
  }

  public void setAvailableUpdate(String version) {
    this.availableUpdate = version == null || version.isBlank() ? null : version.strip();
  }

  /** Попросить оболочку скачать и установить обновление (она остановит сервер сама). */
  public void requestUpdate() {
    event("update", Map.of());
  }

  public boolean restartRequested() {
    return restart.get();
  }

  public void onRestart(Runnable r) {
    this.onRestart = r;
  }
}
