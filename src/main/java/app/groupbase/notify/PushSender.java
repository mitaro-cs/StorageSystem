package app.groupbase.notify;

import app.groupbase.config.GroupbaseProperties;
import app.groupbase.notify.PushSubscriptions.Sub;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Отправка Web Push. Запросы уходят только на домены служб push из настроек (сервер не должен
 * ходить по произвольным адресам из подписки). Мёртвые подписки (404/410) удаляются. В логах —
 * только домен службы и её код причины, без адреса подписки.
 */
@Component
public class PushSender implements DisposableBean {

  /** Содержимое уведомления; url — путь внутри приложения, он же тег для замены дублей. */
  public record Message(String kind, String title, String body, String url, boolean urgent) {}

  /**
   * Итог пробной отправки: сколько устройств приняли уведомление, а если какое-то нет — ответ
   * службы (код HTTP или -1, если до неё не достучались, и её причина, например BadJwtToken).
   */
  public record Report(int devices, int delivered, int status, String reason) {}

  /** Ответ службы на одну отправку. */
  record Outcome(int status, String reason) {
    boolean ok() {
      return status >= 200 && status < 300;
    }
  }

  private static final Logger log = LoggerFactory.getLogger(PushSender.class);

  /** Причина отказа в ответе службы: {@code "reason": "…"} (Apple), errno (Mozilla), message. */
  private static final Pattern REASON =
      Pattern.compile("\"(?:reason|errno|message)\"\\s*:\\s*\"?([^\",}]*)");

  private record Jwt(String token, long exp) {}

  private final PushSubscriptions subs;
  private final VapidKeys keys;
  private final GroupbaseProperties.Push cfg;
  private final Clock clock;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
  private final JsonMapper json = JsonMapper.builder().build();
  private final Map<String, Jwt> jwts = new ConcurrentHashMap<>();

  public PushSender(
      PushSubscriptions subs, VapidKeys keys, GroupbaseProperties props, Clock clock) {
    this.subs = subs;
    this.keys = keys;
    this.cfg = props.push();
    this.clock = clock;
  }

  public boolean enabled() {
    return cfg.enabled();
  }

  /** Адрес подписки ведёт на разрешённую службу push (https; http — только localhost). */
  public boolean allowed(URI u) {
    if (u == null || u.getHost() == null || u.getUserInfo() != null) {
      return false;
    }
    String host = u.getHost().toLowerCase(Locale.ROOT);
    boolean listed =
        cfg.allowedHosts().stream()
            .map(h -> h.trim().toLowerCase(Locale.ROOT))
            .anyMatch(h -> !h.isEmpty() && (host.equals(h) || host.endsWith("." + h)));
    if (!listed) {
      return false;
    }
    return "https".equals(u.getScheme())
        || ("http".equals(u.getScheme()) && (host.equals("127.0.0.1") || host.equals("localhost")));
  }

  /** В фоне: публикация задания не ждёт ответа служб push. */
  public void sendAsync(Collection<Sub> targets, Message m) {
    if (!enabled() || targets.isEmpty()) {
      return;
    }
    byte[] payload = payload(m);
    for (Sub s : targets) {
      pool.execute(() -> deliver(s, payload, m.urgent()));
    }
  }

  /** Сразу и с результатом — для кнопки «Проверить уведомления»: сколько устройств приняли. */
  public Report sendNow(Collection<Sub> targets, Message m) {
    if (!enabled()) {
      return new Report(targets.size(), 0, 0, "");
    }
    byte[] payload = payload(m);
    List<Future<Outcome>> results = new ArrayList<>();
    for (Sub s : targets) {
      results.add(pool.submit(() -> deliver(s, payload, m.urgent())));
    }
    int ok = 0;
    Outcome failed = null;
    for (Future<Outcome> f : results) {
      try {
        Outcome o = f.get();
        if (o.ok()) {
          ok++;
        } else {
          failed = o;
        }
      } catch (ExecutionException e) {
        failed = new Outcome(-1, "");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return failed == null
        ? new Report(targets.size(), ok, 0, "")
        : new Report(targets.size(), ok, failed.status(), failed.reason());
  }

  Outcome deliver(Sub s, byte[] payload, boolean urgent) {
    URI uri;
    try {
      uri = URI.create(s.endpoint());
    } catch (IllegalArgumentException e) {
      subs.deleteById(s.id());
      return new Outcome(410, "");
    }
    if (!allowed(uri)) {
      subs.deleteById(s.id());
      return new Outcome(410, "");
    }
    try {
      byte[] body =
          PushCrypto.encrypt(payload, PushCrypto.unb64(s.p256dh()), PushCrypto.unb64(s.auth()));
      HttpRequest req =
          HttpRequest.newBuilder(uri)
              .timeout(Duration.ofSeconds(15))
              .header("TTL", "86400")
              .header("Urgency", urgent ? "high" : "normal")
              .header("Content-Encoding", "aes128gcm")
              .header("Content-Type", "application/octet-stream")
              .header("Authorization", "vapid t=" + jwt(uri) + ", k=" + keys.publicKey())
              .POST(HttpRequest.BodyPublishers.ofByteArray(body))
              .build();
      HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
      int status = res.statusCode();
      if (status >= 200 && status < 300) {
        subs.ok(s.id(), clock.millis());
        return new Outcome(status, "");
      }
      String reason = reason(res.body());
      if (status == 404 || status == 410) {
        subs.deleteById(s.id());
      } else {
        subs.failed(s.id());
        log.warn(
            "Служба push {} ответила {}{}",
            uri.getHost(),
            status,
            reason.isEmpty() ? "" : " (" + reason + ")");
      }
      return new Outcome(status, reason);
    } catch (IOException | IllegalArgumentException e) {
      subs.failed(s.id());
      log.warn("Не удалось отправить push в {}: {}", uri.getHost(), e.getClass().getSimpleName());
      return new Outcome(-1, "");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Outcome(-1, "");
    }
  }

  /**
   * Код причины из ответа службы: у Apple — {@code {"reason":"BadJwtToken"}}, у Mozilla — {@code
   * errno}, у Google — первая строка текста. Только латиница и цифры, не длиннее 60 символов: в лог
   * и в интерфейс не должно попасть ничего лишнего.
   */
  static String reason(byte[] body) {
    if (body == null || body.length == 0) {
      return "";
    }
    String text = new String(body, 0, Math.min(body.length, 2000), StandardCharsets.UTF_8);
    Matcher m = REASON.matcher(text);
    String r = m.find() ? m.group(1) : text.lines().findFirst().orElse("");
    r = r.replaceAll("[^A-Za-z0-9 _.:-]", "").strip();
    return r.length() > 60 ? r.substring(0, 60) : r;
  }

  /**
   * JWT на 12 часов для службы; переиспользуется, пока до истечения больше часа и не сменился
   * контакт (адрес сайта).
   */
  String jwt(URI endpoint) {
    String aud =
        endpoint.getScheme()
            + "://"
            + endpoint.getHost()
            + (endpoint.getPort() == -1 ? "" : ":" + endpoint.getPort());
    String sub = keys.subject();
    String key = aud + " " + sub;
    long now = clock.millis() / 1000;
    Jwt cached = jwts.get(key);
    if (cached != null && cached.exp() - now > 3600) {
      return cached.token();
    }
    long exp = now + 12 * 3600;
    String token = PushCrypto.vapidJwt(aud, sub, exp, keys.privateKey());
    jwts.put(key, new Jwt(token, exp));
    return token;
  }

  byte[] payload(Message m) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("title", cut(m.title(), 120));
    p.put("body", cut(m.body(), 300));
    p.put("url", m.url());
    p.put("tag", m.url());
    return json.writeValueAsBytes(p);
  }

  private static String cut(String s, int max) {
    return s == null ? "" : s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  @Override
  public void destroy() {
    pool.shutdown();
  }
}
