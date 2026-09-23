package app.groupbase.auth;

import app.groupbase.config.GroupbaseProperties;
import app.groupbase.store.SessionStore;
import app.groupbase.store.User;
import app.groupbase.store.UserStore;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Сессии: клиент получает случайный токен (256 бит), в БД хранится только его SHA-256. Срок
 * скользящий: продлевается не чаще раза в час, чтобы не писать в БД на каждый запрос.
 */
@Service
public class SessionService {

  private static final long TOUCH_EVERY = Duration.ofHours(1).toMillis();

  private final SessionStore sessions;
  private final UserStore users;
  private final Clock clock;
  private final long ttl;
  private final boolean requireStaffTotp;

  public SessionService(
      SessionStore sessions, UserStore users, Clock clock, GroupbaseProperties props) {
    this.sessions = sessions;
    this.users = users;
    this.clock = clock;
    this.ttl = Duration.ofDays(props.auth().sessionDays()).toMillis();
    this.requireStaffTotp = props.auth().requireStaffTotp();
  }

  /** Создаёт сессию и возвращает токен для cookie. */
  public String create(long userId) {
    String token = Tokens.newToken();
    long now = clock.millis();
    sessions.insert(Tokens.sha256(token), userId, now, now + ttl);
    return token;
  }

  public long ttlSeconds() {
    return ttl / 1000;
  }

  /** Пользователь по токену из cookie или пусто, если сессия истекла или аккаунт неактивен. */
  public Optional<Actor> resolve(String token) {
    if (!Tokens.looksValid(token)) {
      return Optional.empty();
    }
    byte[] hash = Tokens.sha256(token);
    long now = clock.millis();
    Optional<SessionStore.Row> row = sessions.find(hash);
    if (row.isEmpty()) {
      return Optional.empty();
    }
    if (row.get().expiresAt() <= now) {
      sessions.delete(hash);
      return Optional.empty();
    }
    Optional<User> user = users.find(row.get().userId());
    if (user.isEmpty() || user.get().status() != User.Status.ACTIVE) {
      sessions.delete(hash);
      return Optional.empty();
    }
    if (now - row.get().lastSeenAt() > TOUCH_EVERY) {
      sessions.touch(hash, now, now + ttl);
    }
    return Optional.of(actor(user.get(), hash));
  }

  public Actor actor(User u, byte[] sessionHash) {
    Actor.Restriction r = null;
    if (u.mustChangePassword()) {
      r = Actor.Restriction.PASSWORD_CHANGE_REQUIRED;
    } else if (requireStaffTotp && u.isStaff() && !u.totpEnabled()) {
      r = Actor.Restriction.TOTP_SETUP_REQUIRED;
    }
    return new Actor(
        u.id(), u.username(), u.displayName(), u.instanceRole(), u.totpEnabled(), r, sessionHash);
  }

  public void revoke(String token) {
    if (Tokens.looksValid(token)) {
      sessions.delete(Tokens.sha256(token));
    }
  }

  public void revokeAll(long userId) {
    sessions.deleteForUser(userId);
  }

  public void revokeOthers(long userId, byte[] keep) {
    sessions.deleteOthers(userId, keep);
  }

  public int purgeExpired() {
    return sessions.deleteExpired(clock.millis());
  }
}
