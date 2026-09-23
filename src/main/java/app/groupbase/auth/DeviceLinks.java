package app.groupbase.auth;

import app.groupbase.audit.AuditService;
import app.groupbase.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Вход по QR-коду, как в мессенджерах: новое устройство показывает QR, человек сканирует его
 * телефоном, где уже вошёл, и подтверждает. Код живёт 2 минуты и срабатывает один раз; в памяти
 * только хеши. Новое устройство узнаёт об одобрении по отдельному секрету опроса, который видит
 * лишь оно.
 */
@Service
public class DeviceLinks {

  public static final Duration TTL = Duration.ofMinutes(2);

  /** Не больше стольких QR-кодов с одного адреса за 10 минут. */
  static final int STARTS_PER_IP = 20;

  public record Started(String code, String poll, long expiresAt) {}

  public record Info(String device, long createdAt, long expiresAt) {}

  private record Link(
      String codeHash, String device, long createdAt, long expiresAt, Long userId) {}

  private final Map<String, Link> byCode = new ConcurrentHashMap<>();
  private final Map<String, String> byPoll = new ConcurrentHashMap<>();
  private final Map<String, long[]> starts = new ConcurrentHashMap<>();
  private final AuditService audit;
  private final Clock clock;

  public DeviceLinks(AuditService audit, Clock clock) {
    this.audit = audit;
    this.clock = clock;
  }

  private static String hash(String token) {
    return HexFormat.of().formatHex(Tokens.sha256(token));
  }

  public Started start(String ip, String device) {
    long now = clock.millis();
    long[] window =
        starts.compute(ip, (k, v) -> v == null || v[0] < now - 600_000 ? new long[] {now, 0} : v);
    synchronized (window) {
      if (++window[1] > STARTS_PER_IP) {
        throw new ApiException(
            HttpStatus.TOO_MANY_REQUESTS, "too_many", "Слишком много попыток, подождите немного");
      }
    }
    String code = Tokens.newToken();
    String poll = Tokens.newToken();
    String label = device == null ? "" : device.strip();
    Link link =
        new Link(
            hash(code),
            label.length() > 60 ? label.substring(0, 60) : label,
            now,
            now + TTL.toMillis(),
            null);
    byCode.put(link.codeHash(), link);
    byPoll.put(hash(poll), link.codeHash());
    return new Started(code, poll, link.expiresAt());
  }

  private Link live(String code) {
    Link l = code == null || !Tokens.looksValid(code) ? null : byCode.get(hash(code));
    if (l == null || l.expiresAt() < clock.millis() || l.userId() != null) {
      throw new ApiException(
          HttpStatus.GONE, "expired", "Код устарел — обновите QR на другом устройстве");
    }
    return l;
  }

  /** Что подтверждает человек: какое устройство и когда просило вход. */
  public Info info(String code) {
    Link l = live(code);
    return new Info(l.device(), l.createdAt(), l.expiresAt());
  }

  public void approve(Actor actor, String code) {
    Link l = live(code);
    Link approved = new Link(l.codeHash(), l.device(), l.createdAt(), l.expiresAt(), actor.id());
    if (!byCode.replace(l.codeHash(), l, approved)) {
      throw new ApiException(HttpStatus.GONE, "expired", "Код уже использован");
    }
    audit.log(actor, null, "user.qr_approve", "user", actor.id());
  }

  /** null — ещё не подтвердили; id пользователя — можно открывать сессию (один раз). */
  public Long poll(String poll) {
    String codeHash = poll == null || !Tokens.looksValid(poll) ? null : byPoll.get(hash(poll));
    Link l = codeHash == null ? null : byCode.get(codeHash);
    if (l == null || (l.userId() == null && l.expiresAt() < clock.millis())) {
      throw new ApiException(HttpStatus.GONE, "expired", "Код устарел — покажите новый");
    }
    if (l.userId() == null) {
      return null;
    }
    byCode.remove(codeHash);
    byPoll.remove(hash(poll));
    return l.userId();
  }

  public void cleanup() {
    long now = clock.millis();
    byCode.values().removeIf(l -> l.expiresAt() < now - TTL.toMillis());
    byPoll.values().removeIf(h -> !byCode.containsKey(h));
    starts.values().removeIf(w -> w[0] < now - 600_000);
  }
}
