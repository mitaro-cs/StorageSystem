package app.groupbase.auth;

import app.groupbase.config.GroupbaseProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Ограничение попыток входа: не больше N неудачных попыток за окно отдельно на IP и на аккаунт.
 * Хранится в памяти — после перезапуска счётчики обнуляются, но постоянная блокировка аккаунта
 * (экспоненциальная задержка) живёт в БД.
 */
@Component
public class LoginThrottle {

  private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();
  private final Clock clock;
  private final int max;
  private final long window;

  public LoginThrottle(Clock clock, GroupbaseProperties props) {
    this.clock = clock;
    this.max = props.auth().maxLoginAttempts();
    this.window = Duration.ofMinutes(props.auth().loginWindowMinutes()).toMillis();
  }

  /** Сколько миллисекунд ждать до следующей попытки; 0 — можно пробовать. */
  public long retryAfter(String ip, String username) {
    return Math.max(wait("ip:" + ip), wait("user:" + norm(username)));
  }

  public void recordFailure(String ip, String username) {
    long now = clock.millis();
    for (String key : new String[] {"ip:" + ip, "user:" + norm(username)}) {
      Deque<Long> q = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
      synchronized (q) {
        q.addLast(now);
        while (q.size() > max) {
          q.removeFirst();
        }
      }
    }
  }

  /** Успешный вход сбрасывает счётчик аккаунта (но не IP). */
  public void recordSuccess(String username) {
    failures.remove("user:" + norm(username));
  }

  /** Удаляет устаревшие записи; вызывается фоновой задачей. */
  public void cleanup() {
    long cutoff = clock.millis() - window;
    failures
        .entrySet()
        .removeIf(
            e -> {
              synchronized (e.getValue()) {
                return e.getValue().isEmpty() || e.getValue().peekLast() < cutoff;
              }
            });
  }

  private long wait(String key) {
    Deque<Long> q = failures.get(key);
    if (q == null) {
      return 0;
    }
    long now = clock.millis();
    synchronized (q) {
      while (!q.isEmpty() && q.peekFirst() <= now - window) {
        q.removeFirst();
      }
      if (q.size() < max) {
        return 0;
      }
      return q.peekFirst() + window - now;
    }
  }

  private static String norm(String username) {
    return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
  }
}
