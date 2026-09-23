package app.groupbase.status;

import app.groupbase.cli.Main;
import app.groupbase.config.GroupbaseProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Есть ли новая версия: раз в 12 часов спрашивает GitHub о последнем выпуске. Никаких данных о
 * сервере не передаётся; выключается {@code groupbase.update-check = false}.
 */
@Service
public class UpdateCheck {

  public record Update(String version, String url) {}

  static final String LATEST =
      "https://api.github.com/repos/mitaro-cs/StorageSystem/releases/latest";
  private static final Duration EVERY = Duration.ofHours(12);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final boolean enabled;
  private final Clock clock;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final AtomicBoolean running = new AtomicBoolean();
  private volatile Update latest;
  private volatile long checkedAt;

  public UpdateCheck(GroupbaseProperties props, Clock clock) {
    this.enabled = props.updateCheck();
    this.clock = clock;
  }

  /** Новая версия, если она есть. Проверка идёт в фоне, ответ не ждёт сеть. */
  public Update available() {
    if (!enabled || Main.version().equals("dev")) {
      return null;
    }
    if (clock.millis() - checkedAt > EVERY.toMillis() && running.compareAndSet(false, true)) {
      Thread.ofVirtual().name("update-check").start(this::refresh);
    }
    Update u = latest;
    return u != null && newer(u.version(), Main.version()) ? u : null;
  }

  private void refresh() {
    try {
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(LATEST))
              .timeout(Duration.ofSeconds(10))
              .header("Accept", "application/vnd.github+json")
              .header("User-Agent", "groupbase")
              .build();
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() == 200) {
        JsonNode j = JSON.readTree(res.body());
        latest =
            new Update(
                j.path("tag_name").asString().replaceFirst("^v", ""),
                j.path("html_url").asString());
      }
    } catch (Exception e) {
      // Нет сети или GitHub недоступен — попробуем позже.
    } finally {
      checkedAt = clock.millis();
      running.set(false);
    }
  }

  /** Сравнение версий вида 1.2.3 (всё, что не цифры, отбрасывается). */
  static boolean newer(String candidate, String current) {
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
