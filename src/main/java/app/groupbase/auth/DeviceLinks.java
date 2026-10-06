package app.groupbase.auth;

import app.groupbase.audit.AuditService;
import app.groupbase.web.ApiException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Вход на другом устройстве (0.7, как просил владелец): устройство, где человек уже вошёл,
 * показывает QR-код и 6 цифр («Профиль → Вход и безопасность → Показать код»), новое сканирует QR
 * камерой или вводит цифры на странице входа — и входит без пароля. Код живёт 3 минуты и
 * срабатывает один раз; показавшее устройство видит, где вошли. В памяти — только хеши длинных
 * кодов.
 *
 * <p>6 цифр подбирать бессмысленно: не больше {@link #MISSES_PER_IP} ошибок с адреса и {@link
 * #MISSES_TOTAL} на весь сайт за 10 минут — дальше ввод цифр закрыт (QR работает).
 */
@Service
public class DeviceLinks {

  public static final Duration TTL = Duration.ofMinutes(3);

  /** Не больше стольких кодов от человека за 10 минут. */
  static final int ISSUES_PER_USER = 20;

  static final int MISSES_PER_IP = 10;
  static final int MISSES_TOTAL = 60;
  private static final long WINDOW = Duration.ofMinutes(10).toMillis();

  public record Issued(String code, String pin, long expiresAt) {}

  /** waiting — ещё не вошли, used — вошли (device — где), expired — код устарел. */
  public record Status(String status, String device) {}

  private record Link(String codeHash, String pin, long userId, long expiresAt, String usedOn) {}

  private final Map<String, Link> byCode = new ConcurrentHashMap<>();
  private final Map<String, String> pins = new ConcurrentHashMap<>();
  private final Map<Long, long[]> issues = new ConcurrentHashMap<>();
  private final Map<String, long[]> misses = new ConcurrentHashMap<>();
  private final long[] missesTotal = {0, 0};
  private final SecureRandom random = new SecureRandom();
  private final AuditService audit;
  private final Clock clock;

  public DeviceLinks(AuditService audit, Clock clock) {
    this.audit = audit;
    this.clock = clock;
  }

  private static String hash(String token) {
    return HexFormat.of().formatHex(Tokens.sha256(token));
  }

  /** Счётчик в окне 10 минут: [начало окна, сколько]. */
  private long[] window(long[] w, long now) {
    if (w[0] < now - WINDOW) {
      w[0] = now;
      w[1] = 0;
    }
    return w;
  }

  /** Код для входа на другом устройстве — от имени того, кто уже вошёл. */
  public Issued issue(Actor actor) {
    long now = clock.millis();
    long[] w = issues.computeIfAbsent(actor.id(), k -> new long[] {now, 0});
    synchronized (w) {
      if (++window(w, now)[1] > ISSUES_PER_USER) {
        throw new ApiException(
            HttpStatus.TOO_MANY_REQUESTS, "too_many", "Слишком много кодов, подождите немного");
      }
    }
    String code = Tokens.newToken();
    String pin;
    do {
      pin = String.format("%06d", random.nextInt(1_000_000));
    } while (pins.putIfAbsent(pin, hash(code)) != null);
    Link link = new Link(hash(code), pin, actor.id(), now + TTL.toMillis(), null);
    byCode.put(link.codeHash(), link);
    return new Issued(code, pin, link.expiresAt());
  }

  /** Что с выданным кодом — спрашивает только тот, кто его показал. */
  public Status status(Actor actor, String code) {
    Link l = code == null || !Tokens.looksValid(code) ? null : byCode.get(hash(code));
    if (l == null || l.userId() != actor.id()) {
      return new Status("expired", null);
    }
    if (l.usedOn() != null) {
      byCode.remove(l.codeHash());
      return new Status("used", l.usedOn());
    }
    return new Status(l.expiresAt() < clock.millis() ? "expired" : "waiting", null);
  }

  /**
   * Новое устройство предъявляет код из QR или 6 цифр. Возвращает, за кого войти; код сгорает.
   *
   * @param device как назвать это устройство показавшему («Safari, Mac»)
   */
  public long redeem(String ip, String code, String pin, String device) {
    long now = clock.millis();
    boolean byPin = code == null || code.isBlank();
    if (byPin) {
      long[] mine = misses.computeIfAbsent(ip, k -> new long[] {now, 0});
      synchronized (missesTotal) {
        synchronized (mine) {
          if (window(mine, now)[1] >= MISSES_PER_IP
              || window(missesTotal, now)[1] >= MISSES_TOTAL) {
            throw new ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "too_many",
                "Слишком много неверных кодов – подождите 10 минут или отсканируйте QR");
          }
        }
      }
    }
    String digits = pin == null ? "" : pin.replaceAll("\\D", "");
    String codeHash =
        byPin
            ? (digits.length() == 6 ? pins.get(digits) : null)
            : Tokens.looksValid(code) ? hash(code) : null;
    Link l = codeHash == null ? null : byCode.get(codeHash);
    if (l == null || l.expiresAt() < now || l.usedOn() != null) {
      if (byPin) {
        synchronized (missesTotal) {
          long[] mine = misses.get(ip);
          synchronized (mine) {
            mine[1]++;
          }
          missesTotal[1]++;
        }
      }
      throw new ApiException(
          HttpStatus.GONE,
          "expired",
          byPin
              ? "Код не подошёл – проверьте цифры на другом устройстве"
              : "Код устарел – покажите новый на другом устройстве");
    }
    String label = device == null ? "" : device.strip();
    label =
        label.isEmpty()
            ? "другое устройство"
            : label.length() > 60 ? label.substring(0, 60) : label;
    Link used = new Link(l.codeHash(), l.pin(), l.userId(), l.expiresAt(), label);
    if (!byCode.replace(l.codeHash(), l, used)) {
      throw new ApiException(HttpStatus.GONE, "expired", "Код уже использован");
    }
    pins.remove(l.pin(), l.codeHash());
    audit.log(null, null, "user.code_login", "user", l.userId(), Map.of("device", label));
    return l.userId();
  }

  public void cleanup() {
    long now = clock.millis();
    byCode.values().removeIf(l -> l.expiresAt() < now - TTL.toMillis());
    pins.values().removeIf(h -> !byCode.containsKey(h));
    issues.values().removeIf(w -> w[0] < now - WINDOW);
    misses.values().removeIf(w -> w[0] < now - WINDOW);
  }
}
