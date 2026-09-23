package app.groupbase.notify;

import app.groupbase.config.GroupbaseProperties;
import app.groupbase.notify.PushSubscriptions.Sub;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Отправка Web Push. Запросы уходят только на домены служб push из настроек (сервер не должен
 * ходить по произвольным адресам из подписки). Мёртвые подписки (404/410) удаляются. В логах —
 * только домен службы, без адреса подписки.
 */
@Component
public class PushSender implements DisposableBean {

  /** Содержимое уведомления; url — путь внутри приложения, он же тег для замены дублей. */
  public record Message(String kind, String title, String body, String url, boolean urgent) {}

  private static final Logger log = LoggerFactory.getLogger(PushSender.class);

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
  public int sendNow(Collection<Sub> targets, Message m) {
    if (!enabled()) {
      return 0;
    }
    byte[] payload = payload(m);
    List<Future<Integer>> results = new ArrayList<>();
    for (Sub s : targets) {
      results.add(pool.submit(() -> deliver(s, payload, m.urgent())));
    }
    int ok = 0;
    for (Future<Integer> f : results) {
      try {
        int status = f.get();
        if (status >= 200 && status < 300) {
          ok++;
        }
      } catch (ExecutionException e) {
        // ошибка уже записана в deliver
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return ok;
  }

  int deliver(Sub s, byte[] payload, boolean urgent) {
    URI uri;
    try {
      uri = URI.create(s.endpoint());
    } catch (IllegalArgumentException e) {
      subs.deleteById(s.id());
      return -1;
    }
    if (!allowed(uri)) {
      subs.deleteById(s.id());
      return -1;
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
      int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
      if (status >= 200 && status < 300) {
        subs.ok(s.id(), clock.millis());
      } else if (status == 404 || status == 410) {
        subs.deleteById(s.id());
      } else {
        subs.failed(s.id());
        log.warn("Служба push {} ответила {}", uri.getHost(), status);
      }
      return status;
    } catch (IOException | IllegalArgumentException e) {
      subs.failed(s.id());
      log.warn("Не удалось отправить push в {}: {}", uri.getHost(), e.getClass().getSimpleName());
      return -1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return -1;
    }
  }

  /** JWT на 12 часов для службы; переиспользуется, пока до истечения больше часа. */
  String jwt(URI endpoint) {
    String aud =
        endpoint.getScheme()
            + "://"
            + endpoint.getHost()
            + (endpoint.getPort() == -1 ? "" : ":" + endpoint.getPort());
    long now = clock.millis() / 1000;
    Jwt cached = jwts.get(aud);
    if (cached != null && cached.exp() - now > 3600) {
      return cached.token();
    }
    long exp = now + 12 * 3600;
    String token = PushCrypto.vapidJwt(aud, keys.subject(), exp, keys.privateKey());
    jwts.put(aud, new Jwt(token, exp));
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
