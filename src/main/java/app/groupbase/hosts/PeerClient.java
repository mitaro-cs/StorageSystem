package app.groupbase.hosts;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import tools.jackson.databind.JsonNode;

/**
 * Второй компьютер хоста говорит с главным — всегда по адресу сайта (туда же ходит группа), поэтому
 * знать адреса друг друга им не нужно. Каждый запрос подписан ключом этого компьютера; CSRF-cookie
 * берётся, как у страниц.
 */
final class PeerClient {

  static final String PEER = "X-Groupbase-Peer";
  static final String AS = "X-Groupbase-As";

  /**
   * Состояние главного: какой сайт, кто отвечает, поколение, номер журнала изменений и версия базы.
   */
  record State(String site, String computer, long epoch, long seq, int schema, String moveTo) {}

  /** Ответ главного на пересланное изменение. */
  record Reply(int status, String contentType, byte[] body) {}

  /** Сопряжение удалось: ключ этого компьютера и что за сайт. */
  record Paired(String token, String site, String computer, long epoch) {}

  /**
   * По адресу сайта отвечает не groupbase — страница туннеля: сайт сейчас нигде не работает. В
   * отличие от обычной ошибки связи (её нет у этого компьютера) — повод стать главным.
   */
  static final class NoServer extends IOException {
    NoServer() {
      super("По адресу сайта сейчас не отвечает Campus");
    }
  }

  private final URI base;
  private final String auth;
  private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
  private final HttpClient http;
  private String csrf;

  PeerClient(String siteUrl, String computerId, String token) throws IOException {
    this.base = TransferClient.normalize(siteUrl);
    this.auth = computerId + " " + token;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(cookies)
            .build();
  }

  /** Сопряжение по коду с экрана главного (без ключа — его выдаст главный). */
  static Paired pair(String siteUrl, String code, String computerId, String name)
      throws IOException {
    PeerClient c = new PeerClient(siteUrl, computerId, "-");
    HttpResponse<String> r =
        c.send(
            c.json("/api/host/peer/pair")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        SiteFolder.JSON.writeValueAsString(
                            Map.of(
                                "code",
                                code == null ? "" : code,
                                "computer",
                                computerId,
                                "name",
                                name))))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (r.statusCode() != 200) {
      throw new IOException(message(r.body()));
    }
    JsonNode j = SiteFolder.JSON.readTree(r.body());
    return new Paired(
        j.path("token").asString(""),
        j.path("site").asString(""),
        j.path("computer").asString(""),
        j.path("epoch").asLong(1));
  }

  /** Попросить подключиться — секрет запроса, по нему потом спрашиваем ответ. */
  String askToJoin(String name) throws IOException {
    String computer = auth.substring(0, auth.indexOf(' '));
    HttpResponse<String> r =
        send(
            json("/api/host/peer/request")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        SiteFolder.JSON.writeValueAsString(
                            Map.of("computer", computer, "name", name == null ? "" : name))))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (r.statusCode() != 200) {
      throw new IOException(message(r.body()));
    }
    return SiteFolder.JSON.readTree(r.body()).path("secret").asString("");
  }

  /** Ответ на запрос: waiting, approved (и код), denied, expired. */
  PeerService.Answer answer(String secret) throws IOException {
    HttpResponse<String> r =
        send(
            json("/api/host/peer/request/answer")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        SiteFolder.JSON.writeValueAsString(Map.of("secret", secret))))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (r.statusCode() != 200) {
      throw new IOException(message(r.body()));
    }
    JsonNode j = SiteFolder.JSON.readTree(r.body());
    String code = j.path("code").asString("");
    return new PeerService.Answer(
        j.path("status").asString("expired"), code.isEmpty() ? null : code);
  }

  State state() throws IOException {
    HttpResponse<String> r =
        send(
            request("/api/host/peer/state").timeout(Duration.ofSeconds(15)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    if (r.statusCode() == 409 && r.body().contains("\"not_main\"")) {
      // Адрес ведёт на компьютер, который сайт уже отдал: сайт сейчас нигде не работает.
      throw new NoServer();
    }
    if (r.statusCode() != 200) {
      throw new IOException(message(r.body()));
    }
    JsonNode j = SiteFolder.JSON.readTree(r.body());
    return new State(
        j.path("site").asString(""),
        j.path("computer").asString(""),
        j.path("epoch").asLong(0),
        j.path("seq").asLong(0),
        j.path("schema").asInt(0),
        j.path("moveTo").asString(""));
  }

  /** Скачать в файл (временный рядом, потом переименование); gzip — распаковать. */
  void download(String path, Path target, boolean gzip) throws IOException {
    HttpResponse<InputStream> r =
        send(
            request(path).timeout(Duration.ofMinutes(10)).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream());
    if (r.statusCode() != 200) {
      try (InputStream in = r.body()) {
        throw new IOException(message(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
      }
    }
    Files.createDirectories(target.getParent());
    Path part = target.resolveSibling(target.getFileName() + ".part");
    try (InputStream raw = r.body();
        InputStream in = gzip ? new GZIPInputStream(raw) : raw;
        OutputStream out = Files.newOutputStream(part)) {
      in.transferTo(out);
    } catch (IOException e) {
      Files.deleteIfExists(part);
      throw e;
    }
    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
  }

  /** Попросить сайт себе: снимок базы — в файл, ответ — поколение того компьютера. */
  long handover(Path target) throws IOException {
    HttpResponse<InputStream> r =
        send(
            request("/api/host/peer/handover")
                .timeout(Duration.ofMinutes(10))
                .header("X-CSRF-Token", csrf())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    if (r.statusCode() != 200) {
      String body;
      try (InputStream in = r.body()) {
        body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      if (body.contains("\"not_main\"")) {
        throw new NoServer();
      }
      throw new IOException(message(body));
    }
    long epoch = r.headers().firstValueAsLong("X-Groupbase-Epoch").orElse(0);
    Files.createDirectories(target.getParent());
    try (InputStream in = new GZIPInputStream(r.body())) {
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    }
    return epoch;
  }

  /** Файлы на главном: «files/…» и «avatars/…». */
  List<String> files() throws IOException {
    HttpResponse<String> r =
        send(
            request("/api/host/peer/files").timeout(Duration.ofMinutes(1)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    if (r.statusCode() != 200) {
      throw new IOException(message(r.body()));
    }
    List<String> out = new ArrayList<>();
    for (JsonNode n : SiteFolder.JSON.readTree(r.body())) {
      out.add(n.asString(""));
    }
    return out;
  }

  void getFile(String name, Path target) throws IOException {
    download(
        "/api/host/peer/file?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8),
        target,
        false);
  }

  void putFile(String name, Path file) throws IOException {
    HttpResponse<String> r =
        send(
            request("/api/host/peer/file?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/octet-stream")
                .header("X-CSRF-Token", csrf())
                .PUT(HttpRequest.BodyPublishers.ofFile(file))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (r.statusCode() != 200) {
      throw new IOException(message(r.body()));
    }
  }

  /** Переслать изменение главному от имени человека; ответ — как есть. */
  Reply forward(String method, String uri, String contentType, long userId, Path body)
      throws IOException {
    HttpRequest.BodyPublisher publisher =
        body != null && Files.isRegularFile(body) && Files.size(body) > 0
            ? HttpRequest.BodyPublishers.ofFile(body)
            : HttpRequest.BodyPublishers.noBody();
    HttpRequest.Builder b =
        request(uri)
            .timeout(Duration.ofMinutes(5))
            .header(AS, String.valueOf(userId))
            .header("X-CSRF-Token", csrf())
            .method(method, publisher);
    if (contentType != null && !contentType.isBlank()) {
      b.header("Content-Type", contentType);
    }
    HttpResponse<byte[]> r = send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    return new Reply(r.statusCode(), r.headers().firstValue("Content-Type").orElse(null), r.body());
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(base.resolve(path))
        .header(PEER, auth)
        .header("Accept", "application/json, */*");
  }

  private HttpRequest.Builder json(String path) throws IOException {
    return request(path)
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/json")
        .header("X-CSRF-Token", csrf());
  }

  /** CSRF-cookie от главного — один раз на клиента. */
  private synchronized String csrf() throws IOException {
    if (csrf != null) {
      return csrf;
    }
    send(
        HttpRequest.newBuilder(base.resolve("/api/health"))
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.discarding());
    for (HttpCookie c : cookies.getCookieStore().getCookies()) {
      if (c.getName().endsWith("gb_csrf")) {
        csrf = c.getValue();
        return csrf;
      }
    }
    throw new IOException("Главный компьютер не выдал ключ защиты запроса – обновите там Campus");
  }

  /** Ответ не от groupbase (страница туннеля) — {@link NoServer}; нет связи — IOException. */
  private <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler)
      throws IOException {
    HttpResponse<T> r;
    try {
      r = http.send(req, handler);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Прервано", e);
    }
    if (!fromGroupServer(r.headers(), r.statusCode())) {
      if (r.body() instanceof InputStream in) {
        in.close();
      }
      throw new NoServer();
    }
    return r;
  }

  /**
   * Ответил сам Campus: метка X-Groupbase, а если туннель её потерял – любой не-HTML ответ (наши
   * ручки отдают JSON, снимки и файлы). Страница ошибки туннеля – HTML (0.9.7: без этого потеря
   * метки выглядела как «основной выключен», и копия сама становилась вторым основным).
   */
  static boolean fromGroupServer(java.net.http.HttpHeaders h) {
    return fromGroupServer(h, 200);
  }

  /** То же с кодом ответа: 502–504 без метки – всегда туннель («до сервера не достучался»). */
  static boolean fromGroupServer(java.net.http.HttpHeaders h, int status) {
    if (h.firstValue("X-Groupbase").isPresent()) {
      return true;
    }
    if (status >= 502 && status <= 504) {
      return false;
    }
    String type = h.firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT);
    return !type.isEmpty() && !type.startsWith("text/html");
  }

  static String message(String body) {
    try {
      String m = SiteFolder.JSON.readTree(body).path("message").asString("");
      if (!m.isBlank()) {
        return m;
      }
    } catch (RuntimeException e) {
      // не JSON
    }
    return "Главный компьютер не ответил как надо – попробуйте ещё раз";
  }
}
