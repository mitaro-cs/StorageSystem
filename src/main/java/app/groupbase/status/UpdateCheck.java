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
      URI.create("https://api.github.com/repos/mitaro-cs/Campus/releases/latest");
  private static final Duration EVERY = Duration.ofHours(12);

  /** Кнопку можно нажимать сколько угодно: удачный ответ GitHub помним 15 секунд. */
  private static final Duration AGAIN = Duration.ofSeconds(15);

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final boolean enabled;
  private final Clock clock;
  private final URI source;
  private final Supplier<String> current;
  // Перенаправления – по ним: репозиторий переименовали (StorageSystem → Campus), и GitHub на
  // прежний адрес отвечал 301 – «Не удалось проверить» (1.0.2). NORMAL не уходит с https на http.
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();
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
                ? "это сборка для разработки – у неё нет выпусков"
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
            case 403, 429 -> "GitHub временно ограничил проверки – попробуйте через час";
            case 404 -> "на GitHub пока нет выпусков";
            default -> "GitHub ответил кодом " + res.statusCode();
          };
    } catch (HttpTimeoutException e) {
      error = "GitHub не ответил за 10 секунд";
    } catch (IOException e) {
      error = "нет связи с GitHub – проверьте интернет на этом компьютере";
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

  /**
   * Сравнение версий по SemVer: 1.0.2-beta.1 < 1.0.2-beta.2 < 1.0.2. Раньше всё, кроме цифр,
   * отбрасывалось, и бета 1.0.2-beta.1 (1.0.2.1) считалась новее выпуска 1.0.2 – тем, кто ставил
   * бету, выпуск не предлагался.
   */
  public static boolean newer(String candidate, String current) {
    return compare(candidate, current) > 0;
  }

  static int compare(String a, String b) {
    String[] x = split(a);
    String[] y = split(b);
    int c = compareIds(x[0].split("\\."), y[0].split("\\."), true);
    if (c != 0) {
      return c;
    }
    // Без предварительной части версия старше той же с ней (1.0.2 > 1.0.2-beta.1).
    if (x[1].isEmpty() || y[1].isEmpty()) {
      return Boolean.compare(x[1].isEmpty(), y[1].isEmpty());
    }
    return compareIds(x[1].split("\\."), y[1].split("\\."), false);
  }

  /** Основная часть и предварительная (после «-»), без «v» в начале и сборки после «+». */
  private static String[] split(String v) {
    String s = v.strip().replaceFirst("^[vV]", "");
    int plus = s.indexOf('+');
    if (plus >= 0) {
      s = s.substring(0, plus);
    }
    int dash = s.indexOf('-');
    return dash < 0
        ? new String[] {s, ""}
        : new String[] {s.substring(0, dash), s.substring(dash + 1)};
  }

  /**
   * Части по очереди: числа – как числа, число младше слова, слова – по алфавиту. В основной части
   * недостающее – ноль (1.0 = 1.0.0), в предварительной более длинная старше (beta < beta.1).
   */
  private static int compareIds(String[] a, String[] b, boolean core) {
    for (int i = 0; i < Math.max(a.length, b.length); i++) {
      if (!core && (i >= a.length || i >= b.length)) {
        return Integer.compare(a.length, b.length);
      }
      String x = i < a.length ? a[i] : "0";
      String y = i < b.length ? b[i] : "0";
      long nx = number(x);
      long ny = number(y);
      int c;
      if (core || (nx >= 0 && ny >= 0)) {
        c = Long.compare(Math.max(nx, 0), Math.max(ny, 0));
      } else if (nx >= 0 || ny >= 0) {
        c = nx >= 0 ? -1 : 1;
      } else {
        c = x.compareTo(y);
      }
      if (c != 0) {
        return c;
      }
    }
    return 0;
  }

  /** Число из части версии или -1, если там не только цифры. */
  private static long number(String part) {
    if (part.isEmpty() || part.length() > 18 || !part.chars().allMatch(Character::isDigit)) {
      return -1;
    }
    return Long.parseLong(part);
  }
}
