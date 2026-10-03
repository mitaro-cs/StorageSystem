package app.groupbase.web.api;

import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Authz;
import app.groupbase.auth.Permission;
import app.groupbase.desktop.DesktopBridge;
import app.groupbase.hosts.PeerService;
import app.groupbase.web.ApiException;
import app.groupbase.web.AuthFilter;
import app.groupbase.web.Public;
import app.groupbase.web.Requests;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Два компьютера хоста напрямую (см. {@link PeerService}): управление — {@code /api/host/peers},
 * обмен данными между компьютерами — {@code /api/host/peer/…} (по ключу компьютера). Эти адреса
 * открыты и на втором компьютере, и пока главный проверяет, не работает ли сайт где-то ещё.
 */
@RestController
@Public
class PeerController {

  record JoinBody(String url, String code) {}

  record PairBody(String code, String computer, String name) {}

  private final PeerService peers;
  private final DesktopBridge bridge;
  private final Authz authz;
  private final AuditService audit;

  PeerController(PeerService peers, DesktopBridge bridge, Authz authz, AuditService audit) {
    this.peers = peers;
    this.bridge = bridge;
    this.authz = authz;
    this.audit = audit;
  }

  // ---------- управление ----------

  @GetMapping("/api/host/peers")
  PeerService.View view(Actor actor, HttpServletRequest req) {
    if (!here(req)) {
      requireAdmin(actor);
    }
    return peers.view();
  }

  /** Код для второго компьютера — берёт администратор (и с телефона, как код переноса). */
  @PostMapping("/api/host/peers/code")
  PeerService.View code(Actor actor) {
    requireAdmin(actor);
    run(peers::newCode);
    audit.log(actor, null, "hosts.peer_code", "instance", null);
    return peers.view();
  }

  @DeleteMapping("/api/host/peers/{computer}")
  PeerService.View remove(Actor actor, @PathVariable String computer) {
    requireAdmin(actor);
    run(
        () -> {
          peers.removePeer(computer);
          return null;
        });
    audit.log(actor, null, "hosts.peer_remove", "instance", null);
    return peers.view();
  }

  @PostMapping("/api/host/peers/sync")
  PeerService.View sync(Actor actor, HttpServletRequest req) {
    requireWindow(actor, req);
    return run(peers::syncNow);
  }

  /** «Сделать главным» — когда прежний главный не вернётся. */
  @PostMapping("/api/host/peers/main")
  PeerService.View makeMain(Actor actor, HttpServletRequest req) {
    requireWindow(actor, req);
    PeerService.View v = run(peers::makeMain);
    audit.log(actor, null, "hosts.peer_main", "instance", null);
    return v;
  }

  /** Подключить этот компьютер вторым — по адресу сайта и коду с главного. */
  @PostMapping("/api/host/peers/join")
  Map<String, String> join(Actor actor, HttpServletRequest req, @RequestBody JoinBody b) {
    requireWindow(actor, req);
    String message = run(() -> peers.join(b.url(), b.code()));
    return Map.of("status", "restarting", "message", message);
  }

  // ---------- обмен между компьютерами ----------

  /** Второй компьютер пришёл с кодом — без ключа (ключ он получит здесь). */
  @PostMapping("/api/host/peer/pair")
  Map<String, Object> pair(@RequestBody PairBody b) {
    Map<String, Object> p = run(() -> peers.pair(b.code(), b.computer(), b.name()));
    audit.log(null, null, "hosts.peer_add", "instance", null, Map.of("name", nameOf(b.name())));
    return p;
  }

  @GetMapping("/api/host/peer/state")
  Map<String, Object> state(HttpServletRequest req) {
    requirePeer(req);
    return run(peers::state);
  }

  @GetMapping("/api/host/peer/copy")
  void copy(HttpServletRequest req, HttpServletResponse res) throws IOException {
    requirePeer(req);
    requireMain();
    res.setContentType("application/zip");
    res.setHeader("Cache-Control", "no-store");
    peers.writeCopy(res.getOutputStream());
  }

  @GetMapping("/api/host/peer/db")
  void db(HttpServletRequest req, HttpServletResponse res) throws IOException {
    requirePeer(req);
    requireMain();
    res.setContentType("application/gzip");
    res.setHeader("Cache-Control", "no-store");
    peers.writeDb(res.getOutputStream());
  }

  @GetMapping("/api/host/peer/files")
  List<String> files(HttpServletRequest req) throws IOException {
    requirePeer(req);
    requireMain();
    return peers.files();
  }

  @GetMapping("/api/host/peer/file")
  void file(HttpServletRequest req, HttpServletResponse res, @RequestParam String name)
      throws IOException {
    requirePeer(req);
    requireMain();
    Path p = run(() -> peers.file(name));
    if (!Files.isRegularFile(p)) {
      throw ApiException.notFound();
    }
    res.setContentType("application/octet-stream");
    res.setHeader("Cache-Control", "no-store");
    Files.copy(p, res.getOutputStream());
  }

  @PutMapping("/api/host/peer/file")
  Map<String, String> putFile(HttpServletRequest req, @RequestParam String name)
      throws IOException {
    requirePeer(req);
    try (InputStream in = req.getInputStream()) {
      peers.putFile(name, in);
    } catch (PeerService.Problem e) {
      throw ApiException.badRequest(e.getMessage());
    }
    return Map.of("status", "ok");
  }

  // ---------- проверки ----------

  private static String nameOf(String name) {
    String n = name == null ? "" : name.strip();
    return n.length() > 40 ? n.substring(0, 40) : n;
  }

  private void requireMain() {
    run(
        () -> {
          peers.requireMain();
          return null;
        });
  }

  /** Запрос подписан ключом сопряжённого компьютера (проверил AuthFilter). */
  private static void requirePeer(HttpServletRequest req) {
    if (req.getAttribute(AuthFilter.PEER) == null) {
      throw ApiException.forbidden("Нужен ключ компьютера хоста");
    }
  }

  private void requireAdmin(Actor actor) {
    if (actor == null) {
      throw ApiException.unauthorized();
    }
    if (!authz.can(actor, Permission.MANAGE_INSTANCE, null)) {
      throw ApiException.forbidden();
    }
  }

  /** Запрос с этого же компьютера — окно приложения хоста (не через туннель). */
  private boolean here(HttpServletRequest req) {
    return bridge.enabled() && Requests.fromThisComputer(req);
  }

  private void requireWindow(Actor actor, HttpServletRequest req) {
    if (!here(req)) {
      throw ApiException.forbidden("Доступно только в приложении на компьютере хоста");
    }
    requireAdmin(actor);
    if (!actor.local()) {
      throw ApiException.forbidden("Доступно только в приложении на компьютере хоста");
    }
  }

  private static <T> T run(Supplier<T> action) {
    try {
      return action.get();
    } catch (PeerService.Problem e) {
      throw ApiException.badRequest(e.getMessage());
    }
  }
}
