package app.groupbase.web.api;

import app.groupbase.accounts.InstanceSettings;
import app.groupbase.accounts.PublicUrl;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Passkeys;
import app.groupbase.web.ApiException;
import app.groupbase.web.Public;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Вход по отпечатку или лицу: добавить ключ в профиле и войти им. */
@RestController
class PasskeyController {

  record PasswordBody(String password) {}

  private final Passkeys passkeys;
  private final PublicUrl publicUrl;
  private final InstanceSettings settings;
  private final Http http;

  PasskeyController(Passkeys passkeys, PublicUrl publicUrl, InstanceSettings settings, Http http) {
    this.passkeys = passkeys;
    this.publicUrl = publicUrl;
    this.settings = settings;
    this.http = http;
  }

  @PostMapping("/api/me/passkeys/options")
  Map<String, Object> createOptions(
      Actor actor, @RequestBody PasswordBody b, HttpServletRequest req) {
    return passkeys.registrationOptions(
        actor, b.password(), origin(req), req.getRemoteAddr(), settings.name());
  }

  @PostMapping("/api/me/passkeys")
  Passkeys.Key create(Actor actor, @RequestBody Passkeys.NewKey k, HttpServletRequest req) {
    return passkeys.register(actor, k, origin(req));
  }

  @GetMapping("/api/me/passkeys")
  List<Passkeys.Key> list(Actor actor) {
    return passkeys.list(actor);
  }

  @DeleteMapping("/api/me/passkeys/{id}")
  Map<String, String> delete(Actor actor, @PathVariable long id) {
    passkeys.delete(actor, id);
    return Map.of("status", "ok");
  }

  @Public
  @PostMapping("/api/auth/passkey/options")
  Map<String, Object> loginOptions(HttpServletRequest req) {
    return passkeys.loginOptions(origin(req));
  }

  @Public
  @PostMapping("/api/auth/passkey")
  Map<String, String> login(
      @RequestBody Passkeys.Assertion a, HttpServletRequest req, HttpServletResponse res) {
    return http.startSession(passkeys.login(a, origin(req), req.getRemoteAddr()), res);
  }

  /**
   * Адрес страницы, с которой пришёл запрос (заголовок Origin от браузера). Он должен быть адресом
   * сайта: из настроек (туннель, свой домен) или тем, по которому пришёл сам запрос. По IP ключи не
   * работают — WebAuthn требует доменное имя.
   */
  String origin(HttpServletRequest req) {
    String o = req.getHeader("Origin");
    Set<String> allowed = new HashSet<>();
    publicUrl.get().map(PasskeyController::originOf).ifPresent(allowed::add);
    allowed.add(originOf(req.getRequestURL().toString()));
    if (o == null || !allowed.contains(o.toLowerCase(Locale.ROOT))) {
      throw ApiException.badRequest("Вход по ключу работает только на адресе сайта");
    }
    String host = URI.create(o).getHost();
    if (host == null || host.matches("[\\d.]+") || host.contains(":") || host.startsWith("[")) {
      throw ApiException.badRequest(
          "Вход по ключу работает на адресе с именем сайта, а не с IP-адресом");
    }
    return o.toLowerCase(Locale.ROOT);
  }

  /** «https://Site.ru:443/path» → «https://site.ru». */
  static String originOf(String url) {
    try {
      URI u = URI.create(url.strip());
      String scheme = u.getScheme().toLowerCase(Locale.ROOT);
      int port = u.getPort();
      boolean standard =
          port == -1
              || (scheme.equals("https") && port == 443)
              || (scheme.equals("http") && port == 80);
      return scheme + "://" + u.getHost().toLowerCase(Locale.ROOT) + (standard ? "" : ":" + port);
    } catch (RuntimeException e) {
      return "";
    }
  }
}
