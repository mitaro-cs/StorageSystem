package app.groupbase.auth;

import app.groupbase.audit.AuditService;
import app.groupbase.config.Secrets;
import app.groupbase.store.User;
import app.groupbase.store.UserStore;
import app.groupbase.web.ApiException;
import java.time.Clock;
import org.springframework.stereotype.Service;

/** Подключение и отключение TOTP в профиле. */
@Service
public class TotpService {

  public record Setup(String secret, String uri) {}

  private final UserStore users;
  private final Secrets secrets;
  private final LoginService login;
  private final AuditService audit;
  private final Clock clock;

  public TotpService(
      UserStore users, Secrets secrets, LoginService login, AuditService audit, Clock clock) {
    this.users = users;
    this.secrets = secrets;
    this.login = login;
    this.audit = audit;
    this.clock = clock;
  }

  /** Новый секрет (ещё не включён, пока пользователь не подтвердит кодом). */
  public Setup begin(Actor actor, String issuer) {
    User u = users.find(actor.id()).orElseThrow(ApiException::unauthorized);
    if (u.totpEnabled()) {
      throw ApiException.conflict("totp_enabled", "Двухфакторная аутентификация уже включена");
    }
    byte[] secret = Totp.newSecret();
    users.setTotpSecret(u.id(), secrets.seal(secret, LoginService.TOTP_CONTEXT));
    return new Setup(Totp.base32(secret), Totp.uri(issuer, u.username(), secret));
  }

  public void enable(Actor actor, String code) {
    User u = users.find(actor.id()).orElseThrow(ApiException::unauthorized);
    if (u.totpSecret() == null || u.totpEnabled()) {
      throw ApiException.badRequest("Сначала начните настройку заново");
    }
    byte[] secret = secrets.open(u.totpSecret(), LoginService.TOTP_CONTEXT);
    long step = Totp.verify(secret, code, clock.millis(), null);
    if (step < 0) {
      throw ApiException.invalid("code", "Неверный код. Проверьте время на телефоне");
    }
    users.enableTotp(u.id(), step);
    audit.log(actor, null, "user.totp_enable", "user", u.id());
  }

  public void disable(Actor actor, String code, boolean staffRequired) {
    User u = users.find(actor.id()).orElseThrow(ApiException::unauthorized);
    if (!u.totpEnabled()) {
      return;
    }
    if (staffRequired && u.isStaff()) {
      throw ApiException.forbidden("Для администраторов и модераторов 2FA обязательна");
    }
    if (!login.verifyTotp(u, code)) {
      throw ApiException.invalid("code", "Неверный код");
    }
    users.disableTotp(u.id());
    audit.log(actor, null, "user.totp_disable", "user", u.id());
  }
}
