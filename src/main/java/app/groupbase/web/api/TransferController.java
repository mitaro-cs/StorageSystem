package app.groupbase.web.api;

import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Permission;
import app.groupbase.backup.RestoreStager;
import app.groupbase.desktop.DesktopBridge;
import app.groupbase.hosts.HostService;
import app.groupbase.hosts.TransferService;
import app.groupbase.web.ApiException;
import app.groupbase.web.Public;
import app.groupbase.web.Requests;
import app.groupbase.web.Require;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Перенос сайта на другой компьютер по коду (см. {@link TransferService}). Код выдаёт администратор
 * — в окне хоста или с телефона; скачать копию и подтвердить перенос может любой, у кого есть код;
 * забрать сайт сюда — только окно приложения на этом компьютере.
 */
@RestController
@RequestMapping("/api/host")
class TransferController {

  record CodeBody(String code) {}

  record PullBody(String url, String code) {}

  private final TransferService transfers;
  private final HostService hosts;
  private final DesktopBridge bridge;
  private final AuditService audit;

  TransferController(
      TransferService transfers, HostService hosts, DesktopBridge bridge, AuditService audit) {
    this.transfers = transfers;
    this.hosts = hosts;
    this.bridge = bridge;
    this.audit = audit;
  }

  @Require(Permission.MANAGE_INSTANCE)
  @GetMapping("/transfer/code")
  ResponseEntity<TransferService.CodeView> code() {
    TransferService.CodeView v = transfers.status();
    return v == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(v);
  }

  @Require(Permission.MANAGE_INSTANCE)
  @PostMapping("/transfer/code")
  TransferService.CodeView newCode(Actor actor) {
    try {
      TransferService.CodeView v = transfers.newCode();
      audit.log(actor, null, "hosts.transfer_code", "instance", null);
      return v;
    } catch (HostService.Problem e) {
      throw ApiException.badRequest(e.getMessage());
    }
  }

  @Require(Permission.MANAGE_INSTANCE)
  @DeleteMapping("/transfer/code")
  Map<String, String> cancel() {
    transfers.cancel();
    return Map.of("status", "ok");
  }

  /** Копия сайта для компьютера, который пришёл с кодом. */
  @Public
  @PostMapping("/transfer")
  void transfer(@RequestBody CodeBody b, HttpServletResponse res) throws IOException {
    try {
      transfers.send(b.code(), res);
    } catch (HostService.Problem e) {
      throw ApiException.badRequest(e.getMessage());
    }
  }

  /** Тот компьютер проверил копию — здесь сайт останавливается. */
  @Public
  @PostMapping("/transfer/confirm")
  Map<String, String> confirm(@RequestBody CodeBody b) {
    try {
      transfers.confirm(b.code());
      return Map.of("status", "ok");
    } catch (HostService.Problem e) {
      throw ApiException.badRequest(e.getMessage());
    }
  }

  /** Как идёт перенос сюда — для полоски в окне приложения. */
  @Public
  @GetMapping("/pull")
  TransferService.PullView pullStatus(HttpServletRequest req) {
    requireHere(req);
    return transfers.pullStatus();
  }

  /** «Перенести сюда по коду» на странице ожидания (сайт сейчас не здесь). */
  @Public
  @PostMapping("/pull")
  RestoreStager.Result pull(HttpServletRequest req, @RequestBody PullBody b) {
    requireHere(req);
    if (hosts.serving()) {
      throw ApiException.badRequest("Сайт и так работает на этом компьютере");
    }
    try {
      return new RestoreStager.Result("restarting", transfers.pull(b.url(), b.code()));
    } catch (HostService.Problem e) {
      throw ApiException.badRequest(e.getMessage());
    }
  }

  private void requireHere(HttpServletRequest req) {
    if (!bridge.enabled() || !Requests.fromThisComputer(req)) {
      throw ApiException.forbidden("Доступно только в приложении на компьютере хоста");
    }
  }
}
