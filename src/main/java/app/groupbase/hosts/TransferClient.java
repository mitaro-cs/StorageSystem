package app.groupbase.hosts;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/**
 * Новый компьютер забирает сайт у работающего по коду переноса: скачивает копию (тот же ZIP, что
 * резервная копия) и подтверждает перенос. Ходит по адресу сайта — через туннель или в локальной
 * сети, как обычный клиент (с CSRF-cookie, как у страниц).
 */
final class TransferClient {

  record Progress(long received, long total) {}

  /** Скачано: куда и на каком порту сайт работал (адрес в CloudPub ведёт на него). */
  record Got(Path zip, int port) {}

  private final URI base;
  private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
  private final HttpClient http;

  TransferClient(String siteUrl) throws IOException {
    this.base = normalize(siteUrl);
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(cookies)
            .build();
  }

  URI base() {
    return base;
  }

  /**
   * Адрес, который ввёл человек: «groupbase.cloudpub.ru», «https://…/», «http://192.168.1.5:17380».
   * Через интернет — только https: копия с ключами не должна ехать открытым текстом. http — только
   * в локальной сети.
   */
  static URI normalize(String url) throws IOException {
    String u = url == null ? "" : url.strip();
    if (u.isEmpty()) {
      throw new IOException("Введите адрес сайта");
    }
    if (!u.contains("://")) {
      u = "https://" + u;
    }
    URI parsed;
    try {
      parsed = URI.create(u);
    } catch (IllegalArgumentException e) {
      throw new IOException("Это не похоже на адрес сайта");
    }
    String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
    String host = parsed.getHost();
    if (host == null || !(scheme.equals("https") || scheme.equals("http"))) {
      throw new IOException("Это не похоже на адрес сайта");
    }
    if (scheme.equals("http") && !local(host)) {
      throw new IOException("Через интернет — только адрес https://… (как у ссылки для группы)");
    }
    return URI.create(scheme + "://" + parsed.getRawAuthority());
  }

  /** Адрес в локальной сети или этот же компьютер — только числом, без запросов к DNS. */
  private static boolean local(String host) {
    if (host.equalsIgnoreCase("localhost")) {
      return true;
    }
    if (!host.matches("[0-9.]+") && !host.startsWith("[")) {
      return false;
    }
    try {
      InetAddress a = InetAddress.getByName(host.replace("[", "").replace("]", ""));
      return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress();
    } catch (UnknownHostException e) {
      return false;
    }
  }

  /** Скачать копию сайта по коду в файл. */
  Got download(String code, Path target, Consumer<Progress> progress) throws IOException {
    String csrf = csrf();
    HttpResponse<InputStream> r =
        send(
            HttpRequest.newBuilder(base.resolve("/api/host/transfer"))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .header("Accept", "application/zip, application/json")
                .header("X-CSRF-Token", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(body(code)))
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    requireServer(r);
    if (r.statusCode() != 200) {
      try (InputStream in = r.body()) {
        throw new IOException(message(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
      }
    }
    long total = number(r.headers().firstValue("X-Groupbase-Size").orElse("0"));
    int port = (int) number(r.headers().firstValue("X-Groupbase-Port").orElse("0"));
    Files.createDirectories(target.getParent());
    long got = 0;
    long reported = 0;
    try (InputStream in = r.body();
        OutputStream out = Files.newOutputStream(target)) {
      byte[] buf = new byte[256 * 1024];
      for (int n; (n = in.read(buf)) >= 0; ) {
        out.write(buf, 0, n);
        got += n;
        if (got - reported >= 512 * 1024) {
          reported = got;
          progress.accept(new Progress(got, total));
        }
      }
    }
    progress.accept(new Progress(got, Math.max(total, got)));
    return new Got(target, port);
  }

  /** Подтвердить перенос: тот компьютер остановит сайт у себя. */
  void confirm(String code) throws IOException {
    HttpResponse<String> r =
        send(
            HttpRequest.newBuilder(base.resolve("/api/host/transfer/confirm"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-CSRF-Token", csrf())
                .POST(HttpRequest.BodyPublishers.ofString(body(code)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    requireServer(r);
    if (r.statusCode() != 200) {
      throw new IOException(message(r.body()));
    }
  }

  /** Отвечает ли по адресу сервер groupbase (после переноса прежний компьютер молчит). */
  boolean serverAnswers() {
    try {
      HttpResponse<Void> r =
          send(
              HttpRequest.newBuilder(base.resolve("/api/health"))
                  .timeout(Duration.ofSeconds(10))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      return r.headers().firstValue("X-Groupbase").isPresent();
    } catch (IOException e) {
      return false;
    }
  }

  /** CSRF-cookie от сервера — как у обычной страницы. */
  private String csrf() throws IOException {
    HttpResponse<Void> r =
        send(
            HttpRequest.newBuilder(base.resolve("/api/health"))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding());
    requireServer(r);
    for (HttpCookie c : cookies.getCookieStore().getCookies()) {
      if (c.getName().endsWith("gb_csrf")) {
        return c.getValue();
      }
    }
    throw new IOException("Сервер не выдал ключ защиты запроса — обновите groupbase там");
  }

  private <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler)
      throws IOException {
    try {
      return http.send(req, handler);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Прервано", e);
    } catch (IOException e) {
      throw new IOException(
          "Не удалось связаться с сайтом по адресу "
              + base.getHost()
              + " — проверьте адрес и интернет, и что там открыт groupbase",
          e);
    }
  }

  /** Ответ не от groupbase: туннель показывает свою страницу — компьютер с сайтом выключен. */
  private static void requireServer(HttpResponse<?> r) throws IOException {
    if (r.headers().firstValue("X-Groupbase").isEmpty()) {
      throw new IOException(
          "По этому адресу сейчас не отвечает groupbase — компьютер с сайтом выключен или адрес"
              + " другой");
    }
  }

  private static String body(String code) {
    return SiteFolder.JSON.writeValueAsString(java.util.Map.of("code", code == null ? "" : code));
  }

  private static String message(String body) {
    try {
      JsonNode j = SiteFolder.JSON.readTree(body);
      String m = j.path("message").asString("");
      if (!m.isBlank()) {
        return m;
      }
    } catch (RuntimeException e) {
      // не JSON
    }
    return "Сайт не отдал данные — попробуйте ещё раз";
  }

  private static long number(String s) {
    try {
      return Long.parseLong(s.strip());
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
