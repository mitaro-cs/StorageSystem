package app.groupbase.status;

import app.groupbase.cli.Main;
import app.groupbase.config.GroupbaseProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Есть ли новая версия: раз в 12 часов спрашивает GitHub о последнем выпуске, а по кнопке
 * «Проверить обновления» — сразу. Никаких данных о сервере не передаётся; выключается {@code
 * groupbase.update-check = false}.
 */
@Service
public class UpdateCheck {

  public record Update(String version, String url) {}

  /**
   * Итог последней проверки.
   *
   * @param latest последний выпуск (null — ещё не узнали)
   * @param checkedAt когда проверяли, мс (0 — ни разу)
   * @param error почему не удалось проверить (null — удалось)
   */
  public record Result(String latest, long checkedAt, String error) {}

  static final URI LATEST =
      URI.create("https://api.github.com/repos/mitaro-cs/StorageSystem/releases/latest");
  private static final Duration EVERY = Duration.ofHours(12);

  /** Кнопку можно нажимать сколько угодно: удачный ответ GitHub помним 15 секунд. */
  private static final Duration AGAIN = Duration.ofSeconds(15);

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final boolean enabled;
  private final Clock clock;
  private final URI source;
  private final Supplier<String> current;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final AtomicBoolean running = new AtomicBoolean();
  private final ReentrantLock checking = new ReentrantLock();
  private volatile Update latest;
  private volatile long checkedAt;
  private volatile String error;

  @Autowired
  public UpdateCheck(GroupbaseProperties props, Clock clock) {
    this(props.updateCheck(), clock, LATEST, Main::version);
  }

  UpdateCheck(boolean enabled, Clock clock, URI source, Supplier<String> current) {
    this.enabled = enabled;
    this.clock = clock;
    this.source = source;
    this.current = current;
  }

  /** Новая версия, если она есть. Проверка идёт в фоне, ответ не ждёт сеть. */
  public Update available() {
    if (!enabled || dev()) {
      return null;
    }
    if (clock.millis() - checkedAt > EVERY.toMillis() && running.compareAndSet(false, true)) {
      Thread.ofVirtual()
          .name("update-check")
          .start(
              () -> {
                checking.lock();
                try {
                  refresh();
                } finally {
                  checking.unlock();
                  running.set(false);
                }
              });
    }
    Update u = latest;
    return u != null && newer(u.version(), current.get()) ? u : null;
  }

  /** Проверить сейчас (кнопка «Проверить обновления»): ждёт ответа GitHub до 10 секунд. */
  public Result checkNow() {
    checking.lock();
    try {
      if (!enabled || dev()) {
        error =
            enabled
                ? "это сборка для разработки — у неё нет выпусков"
                : "проверка выключена в настройках сервера (update-check = false)";
        checkedAt = clock.millis();
      } else if (error != null
          || latest == null
          || clock.millis() - checkedAt >= AGAIN.toMillis()) {
        refresh();
      }
    } finally {
      checking.unlock();
    }
    return result();
  }

  /** Итог последней проверки, без запроса к GitHub. */
  public Result result() {
    Update u = latest;
    return new Result(u == null ? null : u.version(), checkedAt, error);
  }

  private boolean dev() {
    return current.get().equals("dev");
  }

  private void refresh() {
    try {
      HttpRequest req =
          HttpRequest.newBuilder(source)
              .timeout(Duration.ofSeconds(10))
              .header("Accept", "application/vnd.github+json")
              .header("User-Agent", "groupbase")
              .build();
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      error =
          switch (res.statusCode()) {
            case 200 -> read(res.body());
            case 403, 429 -> "GitHub временно ограничил проверки — попробуйте через час";
            case 404 -> "на GitHub пока нет выпусков";
            default -> "GitHub ответил кодом " + res.statusCode();
          };
    } catch (HttpTimeoutException e) {
      error = "GitHub не ответил за 10 секунд";
    } catch (IOException e) {
      error = "нет связи с GitHub — проверьте интернет на этом компьютере";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      error = "проверку прервали";
    } catch (RuntimeException e) {
      error = "непонятный ответ GitHub";
    } finally {
      checkedAt = clock.millis();
    }
  }

  /** Последний выпуск из ответа GitHub; возвращает ошибку или null. */
  private String read(String body) {
    JsonNode j = JSON.readTree(body);
    String version = j.path("tag_name").asString("").replaceFirst("^v", "");
    if (version.isBlank()) {
      return "GitHub не назвал последнюю версию";
    }
    latest = new Update(version, j.path("html_url").asString(""));
    return null;
  }

  /** Сравнение версий вида 1.2.3 (всё, что не цифры, отбрасывается). */
  public static boolean newer(String candidate, String current) {
    int[] a = parts(candidate);
    int[] b = parts(current);
    for (int i = 0; i < Math.max(a.length, b.length); i++) {
      int x = i < a.length ? a[i] : 0;
      int y = i < b.length ? b[i] : 0;
      if (x != y) {
        return x > y;
      }
    }
    return false;
  }

  private static int[] parts(String v) {
    String[] p = v.replaceAll("[^0-9.]", "").split("\\.");
    int[] out = new int[p.length];
    for (int i = 0; i < p.length; i++) {
      out[i] = p[i].isEmpty() ? 0 : Integer.parseInt(p[i]);
    }
    return out;
  }
}
