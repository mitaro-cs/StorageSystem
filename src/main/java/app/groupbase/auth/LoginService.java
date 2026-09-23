package app.groupbase.auth;

import app.groupbase.config.Secrets;
import app.groupbase.store.User;
import app.groupbase.store.UserStore;
import app.groupbase.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Вход по имени и паролю, второй шаг TOTP. Ограничение попыток на IP и аккаунт в памяти плюс
 * экспоненциальная блокировка аккаунта в БД после серии неудач.
 */
@Service
public class LoginService {

  /** Результат: либо пользователь (можно создавать сессию), либо билет для ввода кода TOTP. */
  public record Result(User user, String totpTicket) {}

  private record Ticket(long userId, long expiresAt, int[] attempts) {}

  static final String TOTP_CONTEXT = "totp";
  private static final long TICKET_TTL = Duration.ofMinutes(5).toMillis();
  private static final int LOCK_AFTER = 5;
  private static final long LOCK_MAX = Duration.ofHours(24).toMillis();

  private final UserStore users;
  private final LoginThrottle throttle;
  private final Secrets secrets;
  private final Clock clock;
  private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

  public LoginService(UserStore users, LoginThrottle throttle, Secrets secrets, Clock clock) {
    this.users = users;
    this.throttle = throttle;
    this.secrets = secrets;
    this.clock = clock;
  }

  public Result login(String ip, String username, String password) {
    String name = username == null ? "" : username.trim();
    long wait = throttle.retryAfter(ip, name);
    if (wait > 0) {
      throw tooMany(wait);
    }
    User u = users.findByUsername(name).orElse(null);
    long now = clock.millis();
    if (u != null && u.lockedUntil() != null && u.lockedUntil() > now) {
      Passwords.burnTime();
      throttle.recordFailure(ip, name);
      throw tooMany(u.lockedUntil() - now);
    }
    boolean ok =
        u != null
            && u.passwordHash() != null
            && (u.status() == User.Status.ACTIVE || u.status() == User.Status.BLOCKED)
            && Passwords.verify(password, u.passwordHash());
    if (u == null || u.passwordHash() == null) {
      Passwords.burnTime();
    }
    if (!ok) {
      throttle.recordFailure(ip, name);
      if (u != null) {
        int failures = u.failedLogins() + 1;
        Long lock = null;
        if (failures >= LOCK_AFTER) {
          long minutes = 1L << Math.min(failures - LOCK_AFTER, 20);
          lock = now + Math.min(Duration.ofMinutes(minutes).toMillis(), LOCK_MAX);
        }
        users.recordFailedLogin(u.id(), lock);
      }
      throw new ApiException(
          HttpStatus.UNAUTHORIZED, "bad_credentials", "Неверное имя пользователя или пароль");
    }
    if (u.status() == User.Status.BLOCKED) {
      throw new ApiException(HttpStatus.FORBIDDEN, "blocked", "Аккаунт заблокирован");
    }
    throttle.recordSuccess(name);
    users.resetFailedLogins(u.id());
    if (u.totpEnabled()) {
      String ticket = Tokens.newToken();
      tickets.put(ticket, new Ticket(u.id(), now + TICKET_TTL, new int[1]));
      return new Result(null, ticket);
    }
    return new Result(u, null);
  }

  public User loginTotp(String ip, String ticketToken, String code) {
    Ticket t = ticketToken == null ? null : tickets.get(ticketToken);
    long now = clock.millis();
    if (t == null || t.expiresAt() < now) {
      if (ticketToken != null) {
        tickets.remove(ticketToken);
      }
      throw new ApiException(
          HttpStatus.UNAUTHORIZED, "ticket_expired", "Время на ввод кода истекло, войдите заново");
    }
    User u = users.find(t.userId()).orElseThrow(ApiException::unauthorized);
    if (!verifyTotp(u, code)) {
      synchronized (t) {
        if (++t.attempts()[0] >= 5) {
          tickets.remove(ticketToken);
        }
      }
      throttle.recordFailure(ip, u.username());
      throw new ApiException(HttpStatus.UNAUTHORIZED, "bad_code", "Неверный код");
    }
    tickets.remove(ticketToken);
    return u;
  }

  /** Проверяет код и помечает шаг использованным (повтор того же кода не пройдёт). */
  public boolean verifyTotp(User u, String code) {
    if (u.totpSecret() == null) {
      return false;
    }
    byte[] secret = secrets.open(u.totpSecret(), TOTP_CONTEXT);
    long step = Totp.verify(secret, code, clock.millis(), u.totpLastStep());
    return step >= 0 && users.useTotpStep(u.id(), step);
  }

  public void cleanupTickets() {
    long now = clock.millis();
    tickets.values().removeIf(t -> t.expiresAt() < now);
  }

  private static ApiException tooMany(long waitMillis) {
    long minutes = Math.max(1, (waitMillis + 59_999) / 60_000);
    return new ApiException(
        HttpStatus.TOO_MANY_REQUESTS,
        "too_many_attempts",
        "Слишком много попыток входа. Повторите через " + minutes + " мин.",
        Map.of("retryAfterSeconds", waitMillis / 1000));
  }
}
