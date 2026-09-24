package app.groupbase.auth;

import app.groupbase.audit.AuditService;
import app.groupbase.config.Secrets;
import app.groupbase.store.User;
import app.groupbase.store.UserStore;
import app.groupbase.web.ApiException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Вход по отпечатку или лицу (passkeys). Ключ добавляет вошедший человек, подтвердив пароль; при
 * входе он заменяет и пароль, и код 2FA: ключ живёт в устройстве и открывается биометрией или
 * PIN-кодом (userVerification = required).
 *
 * <p>Вызов (challenge) — одноразовый, живёт 5 минут и привязан к адресу сайта, с которого его
 * запросили. Адрес проверяет контроллер: это адрес сайта из настроек или адрес самого запроса.
 */
@Service
public class Passkeys {

  public record Key(long id, String name, long createdAt, Long lastUsedAt) {}

  /**
   * Ответ браузера при создании ключа (всё — base64url).
   *
   * @param publicKey SubjectPublicKeyInfo из {@code response.getPublicKey()}
   */
  public record NewKey(
      String credentialId,
      String clientDataJSON,
      String authenticatorData,
      String publicKey,
      Integer algorithm,
      String name) {}

  /** Ответ браузера при входе (всё — base64url). */
  public record Assertion(
      String credentialId,
      String clientDataJSON,
      String authenticatorData,
      String signature,
      String userHandle) {}

  /**
   * @param seq порядок выдачи: по нему вытесняются старые вызовы одного адреса
   */
  private record Pending(
      Long userId, boolean register, String origin, String ip, long expiresAt, long seq) {}

  private record Stored(
      long id, long userId, byte[] publicKey, int algorithm, long signCount, String rpId) {}

  static final Duration TTL = Duration.ofMinutes(5);
  static final int MAX_KEYS = 10;
  private static final int MAX_PENDING = 5_000;

  /**
   * Сколько неиспользованных вызовов держим на один адрес: иначе кто-то один заполнил бы все {@link
   * #MAX_PENDING} и на 5 минут закрыл вход по ключу остальным. Новый вытесняет старый.
   */
  static final int PER_IP = 5;

  private final Map<String, Pending> pending = new ConcurrentHashMap<>();
  private final AtomicLong seq = new AtomicLong();
  private final JdbcClient db;
  private final UserStore users;
  private final LoginThrottle throttle;
  private final AuditService audit;
  private final Secrets secrets;
  private final Clock clock;

  public Passkeys(
      JdbcClient db,
      UserStore users,
      LoginThrottle throttle,
      AuditService audit,
      Secrets secrets,
      Clock clock) {
    this.db = db;
    this.users = users;
    this.throttle = throttle;
    this.audit = audit;
    this.secrets = secrets;
    this.clock = clock;
  }

  /** Домен сайта для WebAuthn: хост адреса без порта. */
  public static String rpId(String origin) {
    return URI.create(origin).getHost().toLowerCase(java.util.Locale.ROOT);
  }

  // ---------- добавление ключа ----------

  /**
   * Параметры для {@code navigator.credentials.create}. Пароль подтверждается заново: иначе
   * украденная сессия могла бы навсегда закрепить чужой ключ. В окне хоста на его компьютере — без
   * пароля.
   */
  public Map<String, Object> registrationOptions(
      Actor actor, String password, String origin, String ip, String siteName) {
    User u = users.find(actor.id()).orElseThrow(ApiException::unauthorized);
    if (!actor.local()) {
      long wait = throttle.retryAfter(ip, u.username());
      if (wait > 0) {
        throw new ApiException(
            HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Слишком много попыток. Подождите");
      }
      if (password == null
          || u.passwordHash() == null
          || !Passwords.verify(password, u.passwordHash())) {
        throttle.recordFailure(ip, u.username());
        throw new ApiException(HttpStatus.FORBIDDEN, "bad_password", "Неверный пароль");
      }
    }
    if (count(u.id()) >= MAX_KEYS) {
      throw ApiException.conflict("too_many", "Ключей уже " + MAX_KEYS + " — удалите старый");
    }
    String challenge =
        challenge(new Pending(u.id(), true, origin, ip, expiry(), seq.incrementAndGet()));
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("challenge", challenge);
    o.put("rp", Map.of("id", rpId(origin), "name", siteName.isBlank() ? "groupbase" : siteName));
    o.put(
        "user",
        Map.of(
            "id", WebAuthn.b64u(handle(u.id())),
            "name", u.username(),
            "displayName", u.displayName()));
    o.put(
        "pubKeyCredParams",
        List.of(
            Map.of("type", "public-key", "alg", WebAuthn.ES256),
            Map.of("type", "public-key", "alg", WebAuthn.EDDSA),
            Map.of("type", "public-key", "alg", WebAuthn.RS256)));
    o.put("timeout", TTL.toMillis());
    o.put("attestation", "none");
    o.put(
        "authenticatorSelection",
        Map.of(
            "residentKey", "required", "requireResidentKey", true, "userVerification", "required"));
    o.put(
        "excludeCredentials",
        credentialIds(u.id()).stream()
            .map(id -> Map.of("type", "public-key", "id", WebAuthn.b64u(id)))
            .toList());
    return o;
  }

  @Transactional
  public Key register(Actor actor, NewKey k, String origin) {
    try {
      byte[] clientJson = WebAuthn.fromB64u(k.clientDataJSON());
      WebAuthn.ClientData c = WebAuthn.clientData(clientJson);
      Pending p = take(c.challenge());
      if (p == null
          || !p.register()
          || p.userId() == null
          || p.userId() != actor.id()
          || !"webauthn.create".equals(c.type())
          || c.crossOrigin()
          || !p.origin().equals(c.origin())
          || !origin.equals(c.origin())) {
        throw new WebAuthn.Invalid("client data");
      }
      String rpId = rpId(origin);
      WebAuthn.AuthData a = WebAuthn.authData(WebAuthn.fromB64u(k.authenticatorData()));
      byte[] id = WebAuthn.fromB64u(k.credentialId());
      if (!WebAuthn.rpMatches(a, rpId)
          || !a.userPresent()
          || !a.userVerified()
          || a.credentialId() == null
          || !MessageDigest.isEqual(a.credentialId(), id)) {
        throw new WebAuthn.Invalid("authenticator data");
      }
      int alg = k.algorithm() == null ? 0 : k.algorithm();
      byte[] spki = WebAuthn.fromB64u(k.publicKey());
      WebAuthn.publicKey(alg, spki);
      if (exists(id)) {
        throw ApiException.conflict("exists", "Этот ключ уже добавлен");
      }
      if (count(actor.id()) >= MAX_KEYS) {
        throw ApiException.conflict("too_many", "Ключей уже " + MAX_KEYS + " — удалите старый");
      }
      long now = clock.millis();
      long keyId =
          db.sql(
                  """
                  INSERT INTO passkeys (user_id, credential_id, public_key, algorithm, sign_count,
                                        rp_id, name, created_at)
                  VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                  """)
              .params(actor.id(), id, spki, alg, a.signCount(), rpId, name(k.name()), now)
              .query(Long.class)
              .single();
      audit.log(actor, null, "user.passkey_add", "user", actor.id());
      return new Key(keyId, name(k.name()), now, null);
    } catch (WebAuthn.Invalid e) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "passkey_invalid",
          "Не получилось добавить ключ. Попробуйте ещё раз");
    }
  }

  public List<Key> list(Actor actor) {
    return db.sql(
            "SELECT id, name, created_at, last_used_at FROM passkeys WHERE user_id = ?"
                + " ORDER BY created_at")
        .param(actor.id())
        .query(
            (rs, i) ->
                new Key(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getLong(3),
                    rs.getObject(4) == null ? null : rs.getLong(4)))
        .list();
  }

  @Transactional
  public void delete(Actor actor, long id) {
    int n =
        db.sql("DELETE FROM passkeys WHERE id = ? AND user_id = ?").params(id, actor.id()).update();
    if (n == 0) {
      throw ApiException.notFound();
    }
    audit.log(actor, null, "user.passkey_remove", "user", actor.id());
  }

  // ---------- вход ----------

  /** Параметры для {@code navigator.credentials.get}: ключ выбирает сам человек. */
  public Map<String, Object> loginOptions(String origin, String ip) {
    Map<String, Object> o = new LinkedHashMap<>();
    o.put(
        "challenge",
        challenge(new Pending(null, false, origin, ip, expiry(), seq.incrementAndGet())));
    o.put("rpId", rpId(origin));
    o.put("timeout", TTL.toMillis());
    o.put("userVerification", "required");
    o.put("allowCredentials", List.of());
    return o;
  }

  /** Проверяет подпись ключа и возвращает пользователя для новой сессии. */
  @Transactional
  public User login(Assertion as, String origin, String ip) {
    // Неудачи считаются по адресу: общий счётчик позволил бы кому угодно закрыть вход по ключу
    // всем.
    String who = "#passkey:" + ip;
    long wait = throttle.retryAfter(ip, who);
    if (wait > 0) {
      throw new ApiException(
          HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Слишком много попыток. Подождите");
    }
    try {
      byte[] clientJson = WebAuthn.fromB64u(as.clientDataJSON());
      WebAuthn.ClientData c = WebAuthn.clientData(clientJson);
      Pending p = take(c.challenge());
      if (p == null
          || p.register()
          || !"webauthn.get".equals(c.type())
          || c.crossOrigin()
          || !p.origin().equals(c.origin())
          || !origin.equals(c.origin())) {
        throw new WebAuthn.Invalid("client data");
      }
      String rpId = rpId(origin);
      byte[] authBytes = WebAuthn.fromB64u(as.authenticatorData());
      WebAuthn.AuthData a = WebAuthn.authData(authBytes);
      if (!WebAuthn.rpMatches(a, rpId) || !a.userPresent() || !a.userVerified()) {
        throw new WebAuthn.Invalid("authenticator data");
      }
      Stored key = find(WebAuthn.fromB64u(as.credentialId()));
      if (key == null || !key.rpId().equals(rpId)) {
        throw new WebAuthn.Invalid("unknown credential");
      }
      if (as.userHandle() != null
          && !as.userHandle().isEmpty()
          && !MessageDigest.isEqual(WebAuthn.fromB64u(as.userHandle()), handle(key.userId()))) {
        throw new WebAuthn.Invalid("user handle");
      }
      if (!WebAuthn.verify(
          key.algorithm(),
          key.publicKey(),
          authBytes,
          clientJson,
          WebAuthn.fromB64u(as.signature()))) {
        throw new WebAuthn.Invalid("signature");
      }
      // Счётчик растёт с каждой подписью; не вырос — возможно, ключ скопирован. У ключей,
      // синхронизируемых между устройствами (iCloud, Google), он всегда 0.
      if ((a.signCount() != 0 || key.signCount() != 0) && a.signCount() <= key.signCount()) {
        throw new WebAuthn.Invalid("sign count");
      }
      User u = users.find(key.userId()).orElseThrow(ApiException::unauthorized);
      if (u.status() == User.Status.BLOCKED) {
        throw new ApiException(HttpStatus.FORBIDDEN, "blocked", "Аккаунт заблокирован");
      }
      if (u.status() != User.Status.ACTIVE) {
        throw new WebAuthn.Invalid("inactive user");
      }
      db.sql("UPDATE passkeys SET sign_count = ?, last_used_at = ? WHERE id = ?")
          .params(a.signCount(), clock.millis(), key.id())
          .update();
      users.resetFailedLogins(u.id());
      return u;
    } catch (WebAuthn.Invalid e) {
      throttle.recordFailure(ip, who);
      throw new ApiException(
          HttpStatus.UNAUTHORIZED,
          "passkey_failed",
          "Ключ не подошёл. Войдите по паролю — и добавьте ключ заново в профиле");
    }
  }

  // ---------- служебное ----------

  /** Постоянный непрозрачный id пользователя для WebAuthn (не раскрывает номер в базе). */
  byte[] handle(long userId) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secrets.appKey(), "HmacSHA256"));
      return Arrays.copyOf(
          mac.doFinal(("passkey-user:" + userId).getBytes(StandardCharsets.UTF_8)), 16);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private long expiry() {
    return clock.millis() + TTL.toMillis();
  }

  private synchronized String challenge(Pending p) {
    long now = clock.millis();
    // Свои старые вызовы с этого адреса вытесняются новыми (из ≤ MAX_PENDING — перебор дешёвый).
    List<Map.Entry<String, Pending>> mine =
        pending.entrySet().stream()
            .filter(e -> e.getValue().ip().equals(p.ip()))
            .sorted(Comparator.comparingLong(e -> e.getValue().seq()))
            .toList();
    for (int i = 0; i <= mine.size() - PER_IP; i++) {
      pending.remove(mine.get(i).getKey());
    }
    if (pending.size() >= MAX_PENDING) {
      pending.values().removeIf(x -> x.expiresAt() <= now);
      if (pending.size() >= MAX_PENDING) {
        throw new ApiException(
            HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Слишком много попыток. Подождите");
      }
    }
    String c = WebAuthn.b64u(Tokens.randomBytes(32));
    pending.put(c, p);
    return c;
  }

  /** Одноразово забирает вызов; просроченный — как не было. */
  private Pending take(String challenge) {
    Pending p = challenge == null ? null : pending.remove(challenge);
    return p == null || p.expiresAt() <= clock.millis() ? null : p;
  }

  private static String name(String raw) {
    String n = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
    if (n.isEmpty()) {
      return "Ключ";
    }
    return n.length() > 40 ? n.substring(0, 40) : n;
  }

  private int count(long userId) {
    return db.sql("SELECT count(*) FROM passkeys WHERE user_id = ?")
        .param(userId)
        .query(Integer.class)
        .single();
  }

  private boolean exists(byte[] credentialId) {
    return db.sql("SELECT count(*) FROM passkeys WHERE credential_id = ?")
            .param(credentialId)
            .query(Integer.class)
            .single()
        > 0;
  }

  private List<byte[]> credentialIds(long userId) {
    return db.sql("SELECT credential_id FROM passkeys WHERE user_id = ?")
        .param(userId)
        .query((rs, i) -> rs.getBytes(1))
        .list();
  }

  private Stored find(byte[] credentialId) {
    return db.sql(
            "SELECT id, user_id, public_key, algorithm, sign_count, rp_id FROM passkeys"
                + " WHERE credential_id = ?")
        .param(credentialId)
        .query(
            (rs, i) ->
                new Stored(
                    rs.getLong(1),
                    rs.getLong(2),
                    rs.getBytes(3),
                    rs.getInt(4),
                    rs.getLong(5),
                    rs.getString(6)))
        .optional()
        .orElse(null);
  }
}
