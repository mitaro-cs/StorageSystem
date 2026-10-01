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
 * Вход по QR-коду, как в мессенджерах: новое устройство показывает QR, человек сканирует его
 * телефоном, где уже вошёл, и подтверждает. Код живёт 3 минуты и срабатывает один раз; в памяти
 * только хеши. Новое устройство узнаёт об одобрении по отдельному секрету опроса, который видит
 * лишь оно.
 *
 * <p>Без камеры — по короткому коду (0.5): рядом с QR те же 3 минуты показаны 6 цифр, их вводят на
 * устройстве, где уже вошли ({@link #byPin}). Вводит только вошедший, и ошибок не больше {@link
 * #PIN_TRIES} за 10 минут — перебором не подобрать; подобранный код дал бы вход в свой же аккаунт.
 */
@Service
public class DeviceLinks {

  public static final Duration TTL = Duration.ofMinutes(3);

  /** Не больше стольких QR-кодов с одного адреса за 10 минут. */
  static final int STARTS_PER_IP = 20;

  /** Ошибок при вводе короткого кода на человека за 10 минут. */
  static final int PIN_TRIES = 10;

  public record Started(String code, String poll, String pin, long expiresAt) {}

  public record Info(String device, long createdAt, long expiresAt) {}

  private record Link(
      String codeHash, String device, long createdAt, long expiresAt, Long userId) {}

  private final Map<String, Link> byCode = new ConcurrentHashMap<>();
  private final Map<String, String> byPoll = new ConcurrentHashMap<>();
  private final Map<String, long[]> starts = new ConcurrentHashMap<>();

  /** Короткий код (6 цифр) → хеш длинного; живёт столько же. */
  private final Map<String, String> pins = new ConcurrentHashMap<>();

  /** Человек → [начало окна, ошибок]. */
  private final Map<Long, long[]> pinMisses = new ConcurrentHashMap<>();

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
    String pin;
    do {
      pin = String.format("%06d", random.nextInt(1_000_000));
    } while (pins.putIfAbsent(pin, link.codeHash()) != null);
    return new Started(code, poll, pin, link.expiresAt());
  }

  /**
   * Запись по короткому коду, введённому на устройстве, где уже вошли. Неверный или устаревший код
   * — ошибка этого человека; после {@link #PIN_TRIES} ошибок за 10 минут — пауза.
   */
  private Link livePin(Actor actor, String pin) {
    long now = clock.millis();
    long[] window =
        pinMisses.compute(
            actor.id(), (k, v) -> v == null || v[0] < now - 600_000 ? new long[] {now, 0} : v);
    synchronized (window) {
      if (window[1] >= PIN_TRIES) {
        throw new ApiException(
            HttpStatus.TOO_MANY_REQUESTS,
            "too_many",
            "Слишком много неверных кодов — подождите 10 минут или отсканируйте QR");
      }
    }
    String digits = pin == null ? "" : pin.replaceAll("\\D", "");
    String codeHash = digits.length() == 6 ? pins.get(digits) : null;
    Link l = codeHash == null ? null : byCode.get(codeHash);
    if (l == null || l.expiresAt() < now || l.userId() != null) {
      synchronized (window) {
        window[1]++;
      }
      throw new ApiException(
          HttpStatus.GONE, "expired", "Код не подошёл — проверьте цифры на другом устройстве");
    }
    return l;
  }

  public Info infoByPin(Actor actor, String pin) {
    Link l = livePin(actor, pin);
    return new Info(l.device(), l.createdAt(), l.expiresAt());
  }

  public void approveByPin(Actor actor, String pin) {
    approve(actor, livePin(actor, pin));
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
    approve(actor, live(code));
  }

  private void approve(Actor actor, Link l) {
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
    pins.values().remove(codeHash);
    return l.userId();
  }

  public void cleanup() {
    long now = clock.millis();
    byCode.values().removeIf(l -> l.expiresAt() < now - TTL.toMillis());
    byPoll.values().removeIf(h -> !byCode.containsKey(h));
    pins.values().removeIf(h -> !byCode.containsKey(h));
    pinMisses.values().removeIf(w -> w[0] < now - 600_000);
    starts.values().removeIf(w -> w[0] < now - 600_000);
  }
}
