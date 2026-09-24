package app.groupbase.access;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * API сервиса fxTunnel (https://fxtun.ru, клиент — github.com/mephistofox/fxtun.dev): вход через
 * браузер по коду (как {@code fxtunnel login}) и проверка, свободен ли адрес.
 */
public class FxTunnelApi {

  public record DeviceCode(String session, String userCode, String authUrl, long expiresInSec) {}

  /** status: pending, authorized, expired. */
  public record DeviceToken(String status, String token) {}

  public record Check(boolean available, String reason) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final URI base;
  // Сервис переезжал между доменами (fxtun.dev → fxtun.ru): перенаправления — нормально.
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();

  public FxTunnelApi(URI base) {
    this.base = base;
  }

  public DeviceCode startLogin() throws IOException {
    JsonNode j =
        send(
            HttpRequest.newBuilder(base.resolve("/api/auth/device/code"))
                .POST(HttpRequest.BodyPublishers.noBody()));
    String code = j.path("user_code").asString("");
    if (code.isEmpty()) {
      throw new IOException("fxTunnel не выдал код входа");
    }
    return new DeviceCode(
        j.path("session_id").asString(""),
        code,
        j.path("auth_url").asString(""),
        j.path("expires_in").asLong(600));
  }

  public DeviceToken poll(String session) throws IOException {
    JsonNode j =
        send(
            HttpRequest.newBuilder(
                    base.resolve(
                        "/api/auth/device/token?session="
                            + URLEncoder.encode(session, StandardCharsets.UTF_8)))
                .GET());
    return new DeviceToken(j.path("status").asString("pending"), j.path("token").asString(null));
  }

  public Check check(String token, String subdomain) throws IOException {
    JsonNode j =
        send(
            HttpRequest.newBuilder(base.resolve("/api/domains/check/" + subdomain))
                .header("Authorization", "Bearer " + token)
                .GET());
    return new Check(j.path("available").asBoolean(false), j.path("reason").asString(""));
  }

  private JsonNode send(HttpRequest.Builder b) throws IOException {
    try {
      HttpResponse<String> res =
          http.send(
              b.timeout(Duration.ofSeconds(15))
                  .header("Accept", "application/json")
                  .header("User-Agent", "groupbase")
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() == 401 || res.statusCode() == 403) {
        throw new Unauthorized();
      }
      if (res.statusCode() / 100 != 2) {
        throw new IOException("fxTunnel ответил " + res.statusCode());
      }
      return JSON.readTree(res.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Запрос прерван", e);
    } catch (tools.jackson.core.JacksonException e) {
      throw new IOException("Непонятный ответ fxTunnel", e);
    }
  }

  /** Токен недействителен — нужно войти заново. */
  public static final class Unauthorized extends IOException {
    public Unauthorized() {
      super("Вход в fxTunnel устарел — войдите заново");
    }
  }
}
