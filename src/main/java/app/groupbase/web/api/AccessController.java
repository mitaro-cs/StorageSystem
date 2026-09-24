package app.groupbase.web.api;

import app.groupbase.access.AccessService;
import app.groupbase.access.FxTunnelApi;
import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Permission;
import app.groupbase.web.ApiException;
import app.groupbase.web.Require;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** «Доступ для группы»: как участники попадают на сайт, если сервер — компьютер хоста. */
@RestController
@RequestMapping("/api/admin/access")
class AccessController {

  record ModeBody(String mode, String subdomain, String url) {}

  record CheckBody(String subdomain) {}

  record CloudPubLoginBody(String email, String password, String token) {}

  private final AccessService access;
  private final AuditService audit;

  AccessController(AccessService access, AuditService audit) {
    this.access = access;
    this.audit = audit;
  }

  @Require(Permission.MANAGE_INSTANCE)
  @GetMapping
  AccessService.View view() {
    return access.view();
  }

  @Require(Permission.MANAGE_INSTANCE)
  @PutMapping
  AccessService.View mode(Actor actor, @RequestBody ModeBody b) {
    String mode = b.mode() == null ? "" : b.mode();
    AccessService.View v =
        handle(
            () ->
                switch (mode) {
                  case "fxtunnel" -> access.enableFxTunnel(b.subdomain());
                  case "cloudpub" -> access.enableCloudPub();
                  case "manual" -> access.enableManual(b.url());
                  case "lan" -> access.enableLan();
                  case "off" -> access.disable();
                  default -> throw ApiException.invalid("mode", "Неизвестный способ доступа");
                });
    audit.log(actor, null, "access.mode", "instance", null, Map.of("mode", mode));
    return v;
  }

  @Require(Permission.MANAGE_INSTANCE)
  @PostMapping("/fxtunnel/login")
  AccessService.View login() {
    return handle(access::startLogin);
  }

  @Require(Permission.MANAGE_INSTANCE)
  @PostMapping("/fxtunnel/logout")
  AccessService.View logout(Actor actor) {
    audit.log(actor, null, "access.logout", "instance", null);
    return handle(access::logout);
  }

  @Require(Permission.MANAGE_INSTANCE)
  @PostMapping("/fxtunnel/check")
  FxTunnelApi.Check check(@RequestBody CheckBody b) {
    return handle(() -> access.check(b.subdomain()));
  }

  /** Вход в CloudPub: почта и пароль (пароль не сохраняется) или ключ API. */
  @Require(Permission.MANAGE_INSTANCE)
  @PostMapping("/cloudpub/login")
  AccessService.View cloudpubLogin(Actor actor, @RequestBody CloudPubLoginBody b) {
    AccessService.View v =
        handle(
            () ->
                b.token() != null && !b.token().isBlank()
                    ? access.cloudpubToken(b.token())
                    : access.cloudpubLogin(b.email(), b.password()));
    audit.log(actor, null, "access.login", "instance", null, Map.of("provider", "cloudpub"));
    return v;
  }

  @Require(Permission.MANAGE_INSTANCE)
  @PostMapping("/cloudpub/logout")
  AccessService.View cloudpubLogout(Actor actor) {
    audit.log(actor, null, "access.logout", "instance", null);
    return handle(access::cloudpubLogout);
  }

  private static <T> T handle(Supplier<T> action) {
    try {
      return action.get();
    } catch (AccessService.Problem e) {
      throw ApiException.invalid(e.field, e.getMessage());
    }
  }
}
