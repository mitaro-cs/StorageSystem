package app.groupbase.accounts;

import app.groupbase.auth.GroupRole;
import app.groupbase.auth.InstanceRole;
import app.groupbase.auth.Tokens;
import app.groupbase.store.Group;
import app.groupbase.store.User;
import app.groupbase.store.UserStore;
import app.groupbase.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Первый запуск: создание администратора и первой группы. Через веб — только с одноразовым кодом из
 * лога сервера, чтобы чужой человек не успел занять свежий инстанс раньше владельца. Через CLI
 * ({@code groupbase init}) код не нужен.
 */
@Service
public class SetupService {

  private static final Logger log = LoggerFactory.getLogger(SetupService.class);

  public record Request(
      String mode,
      String instanceName,
      GroupService.GroupInput group,
      String username,
      String displayName,
      String password,
      boolean adminIsHeadman) {}

  private final UserStore users;
  private final AccountService accounts;
  private final GroupService groups;
  private final InstanceSettings settings;
  private final Environment env;
  private final String setupCode = Tokens.newToken().substring(0, 12);

  public SetupService(
      UserStore users,
      AccountService accounts,
      GroupService groups,
      InstanceSettings settings,
      Environment env) {
    this.env = env;
    this.users = users;
    this.accounts = accounts;
    this.groups = groups;
    this.settings = settings;
  }

  public boolean needed() {
    return users.count() == 0;
  }

  public String setupCode() {
    return setupCode;
  }

  @EventListener(ApplicationReadyEvent.class)
  void announce() {
    if (env.matchesProfiles("serve") && needed()) {
      log.warn(
          "Инстанс ещё не настроен. Откройте сайт и введите код первичной настройки: {}"
              + " (или выполните groupbase init)",
          setupCode);
    }
  }

  /** Настройка через веб: проверяет код из лога. */
  @Transactional
  public User setupWithCode(String code, Request req) {
    if (code == null
        || !MessageDigest.isEqual(
            code.trim().getBytes(StandardCharsets.UTF_8),
            setupCode.getBytes(StandardCharsets.UTF_8))) {
      throw new ApiException(
          HttpStatus.FORBIDDEN, "bad_setup_code", "Неверный код настройки. Он есть в логе сервера");
    }
    return setup(req);
  }

  @Transactional
  public User setup(Request req) {
    if (!needed()) {
      throw ApiException.conflict("already_setup", "Инстанс уже настроен");
    }
    InstanceSettings.Mode mode;
    try {
      mode = InstanceSettings.Mode.of(req.mode() == null ? "single" : req.mode());
    } catch (IllegalArgumentException e) {
      throw ApiException.invalid("mode", "Режим: single или multi");
    }
    settings.set(InstanceSettings.MODE, mode.id());
    String name = req.instanceName() == null ? "" : req.instanceName().strip();
    if (name.length() > 60) {
      throw ApiException.invalid("instanceName", "Название — до 60 символов");
    }
    Group g = req.group() == null ? null : groups.createSystem(req.group());
    if (name.isEmpty() && g != null) {
      name = g.name();
    }
    settings.set(InstanceSettings.NAME, name);
    AccountService.Created admin =
        accounts.createSystem(
            req.username(),
            req.displayName(),
            req.password(),
            InstanceRole.ADMIN,
            g == null ? null : g.id(),
            req.adminIsHeadman() && g != null ? GroupRole.HEADMAN : null);
    log.info("Первичная настройка завершена");
    return users.find(admin.userId()).orElseThrow();
  }
}
