package app.groupbase.web.api;

import app.groupbase.accounts.SetupService;
import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Authz;
import app.groupbase.auth.Permission;
import app.groupbase.desktop.DesktopBridge;
import app.groupbase.hosts.HostService;
import app.groupbase.web.ApiException;
import app.groupbase.web.Public;
import app.groupbase.web.Requests;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Сайт на нескольких компьютерах хоста (см. {@link HostService}). Смотреть состояние может
 * администратор откуда угодно, а менять — только окно приложения на этом компьютере: речь о его
 * папках. Эти адреса открыты и в режиме ожидания — через них страница ожидания и работает.
 */
@RestController
@RequestMapping("/api/host")
@Public
class HostController {

  record CloudBody(String cloud) {}

  record NameBody(String name) {}

  private final HostService hosts;
  private final DesktopBridge bridge;
  private final Authz authz;
  private final SetupService setup;
  private final AuditService audit;

  HostController(
      HostService hosts,
      DesktopBridge bridge,
      Authz authz,
      SetupService setup,
      AuditService audit) {
    this.hosts = hosts;
    this.bridge = bridge;
    this.authz = authz;
    this.setup = setup;
    this.audit = audit;
  }

  @GetMapping
  HostService.View view(Actor actor, HttpServletRequest req) {
    if (!here(req) && !authz.can(actor, Permission.MANAGE_INSTANCE, null)) {
      throw actor == null ? ApiException.unauthorized() : ApiException.forbidden();
    }
    return hosts.view();
  }

  /** Кто отвечает по адресу сайта: другие компьютеры хоста проверяют, не работает ли он где-то. */
  @GetMapping("/whoami")
  Map<String, Object> whoami() {
    return hosts.whoami().orElseThrow(ApiException::notFound);
  }

  @PostMapping("/enable")
  HostService.View enable(Actor actor, HttpServletRequest req, @RequestBody CloudBody b) {
    requireWindow(actor, req);
    HostService.View v = run(() -> hosts.enable(b.cloud()));
    audit.log(actor, null, "hosts.enable", "instance", null);
    return v;
  }

  @PostMapping("/disable")
  HostService.View disable(Actor actor, HttpServletRequest req) {
    requireWindow(actor, req);
    HostService.View v = run(hosts::disable);
    audit.log(actor, null, "hosts.disable", "instance", null);
    return v;
  }

  @PutMapping("/name")
  HostService.View rename(Actor actor, HttpServletRequest req, @RequestBody NameBody b) {
    requireWindow(actor, req);
    return run(() -> hosts.rename(b.name()));
  }

  @PostMapping("/snapshot")
  HostService.View snapshot(Actor actor, HttpServletRequest req) {
    requireWindow(actor, req);
    return run(hosts::saveNow);
  }

  /**
   * «Перенести сюда» / «Запустить здесь» — с этого компьютера, даже без входа: в режиме ожидания
   * войти по паролю нельзя (данные здесь устаревшие), а ссылка входа окна могла истечь. Чужой сайт
   * это не нажмёт (CSRF), через туннель — тоже.
   */
  @PostMapping("/takeover")
  HostService.View takeover(HttpServletRequest req) {
    if (!here(req)) {
      throw ApiException.forbidden("Доступно только в приложении на компьютере хоста");
    }
    return run(hosts::takeOver);
  }

  /**
   * «Вернуть сайт сюда» после переноса по коду, если на новом компьютере он так и не заработал.
   * Только с этого компьютера: в этом состоянии войти по паролю нельзя.
   */
  @PostMapping("/return")
  HostService.View returnHere(HttpServletRequest req) {
    if (!here(req)) {
      throw ApiException.forbidden("Доступно только в приложении на компьютере хоста");
    }
    return run(hosts::returnHere);
  }

  /** Запрос с этого же компьютера — окно приложения хоста (не через туннель). */
  private boolean here(HttpServletRequest req) {
    return bridge.enabled() && Requests.fromThisComputer(req);
  }

  /**
   * Менять — только из окна приложения на этом компьютере: хост с его входом или, пока сайт здесь
   * ещё не настроен (данные только едут), любой запрос отсюда.
   */
  private void requireWindow(Actor actor, HttpServletRequest req) {
    if (!here(req)) {
      throw ApiException.forbidden("Доступно только в приложении на компьютере хоста");
    }
    if (setup.needed()) {
      return;
    }
    if (actor == null) {
      throw ApiException.unauthorized();
    }
    if (!actor.local() || !authz.can(actor, Permission.MANAGE_INSTANCE, null)) {
      throw ApiException.forbidden("Доступно только в приложении на компьютере хоста");
    }
  }

  private static HostService.View run(Supplier<HostService.View> action) {
    try {
      return action.get();
    } catch (HostService.Problem e) {
      throw ApiException.badRequest(e.getMessage());
    }
  }
}
