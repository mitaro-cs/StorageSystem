package app.groupbase.web.api;

import app.groupbase.accounts.AccountService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.DeviceLinks;
import app.groupbase.auth.LoginService;
import app.groupbase.auth.RecoveryCodes;
import app.groupbase.auth.SessionService;
import app.groupbase.store.User;
import app.groupbase.store.UserStore;
import app.groupbase.web.AllowRestricted;
import app.groupbase.web.ApiException;
import app.groupbase.web.Cookies;
import app.groupbase.web.Public;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthController {

  record LoginBody(String username, String password) {}

  record TotpBody(String ticket, String code) {}

  record PasswordBody(String password) {}

  private final LoginService login;
  private final SessionService sessions;
  private final AccountService accounts;
  private final Cookies cookies;
  private final Http http;
  private final RecoveryCodes recovery;
  private final DeviceLinks links;
  private final UserStore users;
  private final app.groupbase.hosts.PeerService peers;

  AuthController(
      LoginService login,
      SessionService sessions,
      AccountService accounts,
      Cookies cookies,
      Http http,
      RecoveryCodes recovery,
      DeviceLinks links,
      UserStore users,
      app.groupbase.hosts.PeerService peers) {
    this.peers = peers;
    this.links = links;
    this.users = users;
    this.login = login;
    this.sessions = sessions;
    this.accounts = accounts;
    this.cookies = cookies;
    this.http = http;
    this.recovery = recovery;
  }

  @Public
  @PostMapping("/login")
  Map<String, String> login(
      @RequestBody LoginBody b, HttpServletRequest req, HttpServletResponse res) {
    LoginService.Result r = login.login(req.getRemoteAddr(), b.username(), b.password());
    if (r.totpTicket() != null) {
      return Map.of("status", "totp", "ticket", r.totpTicket());
    }
    return http.startSession(r.user(), res);
  }

  @Public
  @PostMapping("/login/totp")
  Map<String, String> totp(
      @RequestBody TotpBody b, HttpServletRequest req, HttpServletResponse res) {
    var user = login.loginTotp(req.getRemoteAddr(), b.ticket(), b.code());
    Map<String, String> out = new java.util.HashMap<>(http.startSession(user, res));
    if (RecoveryCodes.looksLikeRecovery(b.code())) {
      // Фронт напомнит, сколько резервных кодов осталось.
      out.put("recoveryLeft", String.valueOf(recovery.remaining(user.id())));
    }
    return out;
  }

  record LinkStatusBody(String code) {}

  record RedeemBody(String code, String pin, String device) {}

  /**
   * Вход на другом устройстве (0.7): здесь, где уже вошли, — код (QR и 6 цифр) для нового
   * устройства.
   */
  @PostMapping("/link")
  DeviceLinks.Issued linkIssue(Actor actor) {
    return links.issue(actor);
  }

  /** Вошли ли уже по коду — опрос с устройства, которое его показывает. */
  @PostMapping("/link/status")
  DeviceLinks.Status linkStatus(Actor actor, @RequestBody LinkStatusBody b) {
    return links.status(actor, b.code());
  }

  /** Новое устройство: код из QR или 6 цифр → сессия. */
  @Public
  @PostMapping("/link/redeem")
  Map<String, String> linkRedeem(
      @RequestBody RedeemBody b, HttpServletRequest req, HttpServletResponse res) {
    // На копии код проверяет основной (0.9.8): там его выдали – «Показать код» в любом окне идёт
    // на основной. Нет связи – проверяем здесь (код могли выдать и здесь, пока связи не было).
    var main = peers.redeemOnMain(b.code(), b.pin(), b.device(), req.getRemoteAddr());
    long userId;
    if (main != null && main.status() == 200) {
      userId = main.userId();
    } else {
      try {
        userId = links.redeem(req.getRemoteAddr(), b.code(), b.pin(), b.device());
      } catch (ApiException e) {
        if (main != null) {
          throw new ApiException(
              org.springframework.http.HttpStatus.valueOf(main.status()),
              main.error(),
              main.message());
        }
        throw e;
      }
    }
    User user = users.find(userId).orElseThrow(ApiException::unauthorized);
    if (user.status() != User.Status.ACTIVE) {
      throw ApiException.forbidden();
    }
    return http.startSession(user, res);
  }

  @AllowRestricted
  @PostMapping("/logout")
  Map<String, String> logout(Actor actor, HttpServletRequest req, HttpServletResponse res) {
    sessions.revoke(cookies.readSession(req));
    cookies.clearSession(req, res);
    return Map.of("status", "ok");
  }

  /** Сведения об одноразовой ссылке активации или сброса пароля. */
  @Public
  @GetMapping("/links/{token}")
  AccountService.TokenInfo link(@PathVariable String token) {
    return accounts.tokenInfo(token);
  }

  @Public
  @PostMapping("/links/{token}")
  Map<String, String> redeem(
      @PathVariable String token, @RequestBody PasswordBody b, HttpServletResponse res) {
    var user = accounts.redeem(token, b.password());
    if (user.totpEnabled()) {
      // Ссылка не должна обходить второй фактор: входим обычным способом с кодом.
      return Map.of("status", "login");
    }
    return http.startSession(user, res);
  }
}
