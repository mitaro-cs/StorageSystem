package app.groupbase;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.IntFunction;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP-клиент для интеграционных тестов: свои cookie (сессия), CSRF-заголовок, JSON. */
public class ApiClient {

  public record Response(int status, JsonNode json, String body, HttpResponse<String> raw) {
    public String error() {
      return json == null || json.get("error") == null ? null : json.get("error").asString();
    }
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final IntFunction<String> base;
  private final CookieManager cookies = new CookieManager();
  private final HttpClient http;
  private final int port;

  public ApiClient(int port) {
    this.port = port;
    this.base = p -> "http://127.0.0.1:" + p;
    this.http = HttpClient.newBuilder().cookieHandler(cookies).build();
  }

  public Response get(String path) {
    return send("GET", path, null);
  }

  public Response post(String path, Object body) {
    return send("POST", path, body);
  }

  public Response put(String path, Object body) {
    return send("PUT", path, body);
  }

  public Response patch(String path, Object body) {
    return send("PATCH", path, body);
  }

  public Response delete(String path) {
    return send("DELETE", path, null);
  }

  public Response delete(String path, Object body) {
    return send("DELETE", path, body);
  }

  /** Запрос без CSRF-заголовка (проверка защиты). */
  public Response postWithoutCsrf(String path, Object body) {
    return send("POST", path, body, false);
  }

  public Response send(String method, String path, Object body) {
    return send(method, path, body, true);
  }

  private Response send(String method, String path, Object body, boolean csrf) {
    try {
      if (csrf && !"GET".equals(method) && csrfToken() == null) {
        get("/api/health");
      }
      HttpRequest.Builder b =
          HttpRequest.newBuilder(URI.create(base.apply(port) + path))
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
      if (body != null) {
        b.header("Content-Type", "application/json");
      }
      if (csrf && csrfToken() != null) {
        b.header("X-CSRF-Token", csrfToken());
      }
      HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      JsonNode json = null;
      String ct = r.headers().firstValue("Content-Type").orElse("");
      if (ct.startsWith("application/json") && !r.body().isEmpty()) {
        json = JSON.readTree(r.body());
      }
      return new Response(r.statusCode(), json, r.body(), r);
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  public String csrfToken() {
    return cookie("gb_csrf");
  }

  public String cookie(String name) {
    for (HttpCookie c : cookies.getCookieStore().getCookies()) {
      if (c.getName().equals(name) && !c.hasExpired()) {
        return c.getValue();
      }
    }
    return null;
  }

  public void clearCookies() {
    cookies.getCookieStore().removeAll();
  }
}
