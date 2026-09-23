package app.groupbase.web.api;

import app.groupbase.accounts.AccountService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.LoginService;
import app.groupbase.auth.SessionService;
import app.groupbase.web.AllowRestricted;
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

  AuthController(
      LoginService login,
      SessionService sessions,
      AccountService accounts,
      Cookies cookies,
      Http http) {
    this.login = login;
    this.sessions = sessions;
    this.accounts = accounts;
    this.cookies = cookies;
    this.http = http;
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
    return http.startSession(login.loginTotp(req.getRemoteAddr(), b.ticket(), b.code()), res);
  }

  @AllowRestricted
  @PostMapping("/logout")
  Map<String, String> logout(Actor actor, HttpServletRequest req, HttpServletResponse res) {
    sessions.revoke(cookies.read(req, cookies.session));
    cookies.clearSession(res);
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
