package app.groupbase.auth;

import app.groupbase.audit.AuditService;
import app.groupbase.config.Secrets;
import app.groupbase.store.User;
import app.groupbase.store.UserStore;
import app.groupbase.web.ApiException;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;

/** Подключение и отключение TOTP в профиле. */
@Service
public class TotpService {

  public record Setup(String secret, String uri) {}

  private final UserStore users;
  private final Secrets secrets;
  private final LoginService login;
  private final AuditService audit;
  private final RecoveryCodes recovery;
  private final Clock clock;

  public TotpService(
      UserStore users,
      Secrets secrets,
      LoginService login,
      AuditService audit,
      RecoveryCodes recovery,
      Clock clock) {
    this.users = users;
    this.secrets = secrets;
    this.login = login;
    this.audit = audit;
    this.recovery = recovery;
    this.clock = clock;
  }

  /**
   * Секрет для подключения (включается, только когда пользователь подтвердит кодом). Пока 2FA не
   * включена, повторный вызов возвращает тот же секрет: обновление страницы не должно ломать уже
   * отсканированный QR-код.
   */
  public Setup begin(Actor actor, String issuer) {
    User u = users.find(actor.id()).orElseThrow(ApiException::unauthorized);
    if (u.totpEnabled()) {
      throw ApiException.conflict("totp_enabled", "Двухфакторная аутентификация уже включена");
    }
    byte[] secret;
    if (u.totpSecret() != null) {
      secret = secrets.open(u.totpSecret(), LoginService.TOTP_CONTEXT);
    } else {
      secret = Totp.newSecret();
      users.setTotpSecret(u.id(), secrets.seal(secret, LoginService.TOTP_CONTEXT));
    }
    return new Setup(Totp.base32(secret), Totp.uri(issuer, u.username(), secret));
  }

  /** Включает 2FA и возвращает резервные коды — их показывают один раз. */
  public List<String> enable(Actor actor, String code) {
    User u = users.find(actor.id()).orElseThrow(ApiException::unauthorized);
    if (u.totpSecret() == null || u.totpEnabled()) {
      throw ApiException.badRequest("Сначала начните настройку заново");
    }
    byte[] secret = secrets.open(u.totpSecret(), LoginService.TOTP_CONTEXT);
    // Часы телефона нередко расходятся с сервером на минуту-другую: при подключении ищем код в
    // окне ±5 минут и запоминаем сдвиг. Подобрать код это не помогает — ключ и так у владельца
    // сессии.
    long now = clock.millis();
    long step = Totp.verify(secret, code, now, null, 0, Totp.MAX_DRIFT);
    if (step < 0) {
      throw ApiException.invalid(
          "code", "Неверный код. Проверьте, что в приложении добавлен ключ с этой страницы");
    }
    users.enableTotp(u.id(), step, Totp.drift(step, now));
    audit.log(actor, null, "user.totp_enable", "user", u.id());
    return recovery.issue(u.id());
  }

  /** Новый набор резервных кодов; старые перестают действовать. Нужен код из приложения. */
  public List<String> regenerateRecovery(Actor actor, String code) {
    User u = users.find(actor.id()).orElseThrow(ApiException::unauthorized);
    if (!u.totpEnabled()) {
      throw ApiException.badRequest("Двухфакторная аутентификация не включена");
    }
    if (!login.verifyTotp(u, code)) {
      throw ApiException.invalid("code", "Неверный код");
    }
    audit.log(actor, null, "user.recovery_codes", "user", u.id());
    return recovery.issue(u.id());
  }

  public int recoveryLeft(Actor actor) {
    return recovery.remaining(actor.id());
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
