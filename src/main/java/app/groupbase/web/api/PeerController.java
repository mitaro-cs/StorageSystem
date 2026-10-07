package app.groupbase.web.api;

import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Authz;
import app.groupbase.auth.Permission;
import app.groupbase.desktop.DesktopBridge;
import app.groupbase.hosts.HostService;
import app.groupbase.hosts.PeerDiscovery;
import app.groupbase.hosts.PeerService;
import app.groupbase.hosts.TransferService;
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
import org.springframework.http.HttpStatus;
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

  record JoinRequestBody(String computer, String name) {}

  record SecretBody(String secret) {}

  record AnswerBody(Boolean allow) {}

  record UrlBody(String url) {}

  record HostBody(String computer) {}

  record KeyBody(String key) {}

  private final PeerService peers;
  private final DesktopBridge bridge;
  private final Authz authz;
  private final AuditService audit;
  private final TransferService transfers;
  private final PeerDiscovery discovery;
  private final HostService hosts;

  PeerController(
      PeerService peers,
      DesktopBridge bridge,
      Authz authz,
      AuditService audit,
      TransferService transfers,
      PeerDiscovery discovery,
      HostService hosts) {
    this.hosts = hosts;
    this.transfers = transfers;
    this.discovery = discovery;
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

  /** Подключить этот компьютер вторым — по адресу сайта и коду с главного. */
  @PostMapping("/api/host/peers/join")
  Map<String, String> join(Actor actor, HttpServletRequest req, @RequestBody JoinBody b) {
    requireWindow(actor, req);
    String message = run(() -> peers.join(b.url(), b.code()));
    return Map.of("status", "restarting", "message", message);
  }

  /** Сайты в локальной сети — для «Подключить» без адреса и кода. */
  @GetMapping("/api/host/peers/discover")
  List<PeerDiscovery.Found> discover(Actor actor, HttpServletRequest req) {
    requireWindow(actor, req);
    return discovery.discover();
  }

  /** Подключить этот компьютер по адресу сайта: там спросят «Разрешить?». */
  @PostMapping("/api/host/peers/ask")
  PeerService.Ask ask(Actor actor, HttpServletRequest req, @RequestBody UrlBody b) {
    requireWindow(actor, req);
    return run(() -> peers.startAsk(b.url()));
  }

  /** «Разрешить» / «Отклонить» запрос другого компьютера. */
  @PostMapping("/api/host/peers/requests/{id}")
  PeerService.View answer(Actor actor, @PathVariable String id, @RequestBody AnswerBody b) {
    requireAdmin(actor);
    boolean allow = Boolean.TRUE.equals(b.allow());
    run(
        () -> {
          peers.answer(id, allow);
          return null;
        });
    if (allow) {
      audit.log(actor, null, "hosts.peer_allow", "instance", null);
    }
    return peers.view();
  }

  /** «Сделать хостом» — этот компьютер или другой из связанных. */
  @PostMapping("/api/host/peers/host")
  PeerService.View makeHost(Actor actor, HttpServletRequest req, @RequestBody HostBody b) {
    requireWindow(actor, req);
    PeerService.View v = run(() -> peers.makeHost(b.computer()));
    audit.log(actor, null, "hosts.peer_host", "instance", null);
    return v;
  }

  /** Два основных: этот уступает тому, кто отвечает по адресу сайта. */
  @PostMapping("/api/host/peers/yield")
  PeerService.View yieldToRival(Actor actor, HttpServletRequest req) {
    requireWindow(actor, req);
    PeerService.View v = run(peers::yieldToRival);
    audit.log(actor, null, "hosts.peer_yield", "instance", null);
    return v;
  }

  /** Ключ сайта — показать (и создать, если ещё нет). */
  @GetMapping("/api/host/peers/key")
  Map<String, String> key(Actor actor, HttpServletRequest req) {
    requireWindow(actor, req);
    return Map.of("key", run(peers::siteKey));
  }

  /** «Сменить ключ»: старым больше не подключиться. */
  @PostMapping("/api/host/peers/key")
  Map<String, String> newKey(Actor actor, HttpServletRequest req) {
    requireWindow(actor, req);
    String key = run(peers::newSiteKey);
    audit.log(actor, null, "hosts.peer_key", "instance", null);
    return Map.of("key", key);
  }

  /** Подключить этот компьютер ключом сайта. */
  @PostMapping("/api/host/peers/join-key")
  Map<String, String> joinKey(Actor actor, HttpServletRequest req, @RequestBody KeyBody b) {
    requireWindow(actor, req);
    return Map.of("status", "restarting", "message", run(() -> peers.joinByKey(b.key())));
  }

  // ---------- экран ожидания: сайт не здесь, входа нет ----------

  @PostMapping("/api/host/standby/key")
  Map<String, String> standbyKey(HttpServletRequest req, @RequestBody KeyBody b) {
    requireStandby(req);
    return Map.of("status", "restarting", "message", run(() -> peers.joinByKey(b.key())));
  }

  /** Поиск сайта в сети — с экрана ожидания, только с этого компьютера (входа там нет). */
  @GetMapping("/api/host/standby/discover")
  List<PeerDiscovery.Found> standbyDiscover(HttpServletRequest req) {
    requireStandby(req);
    return discovery.discover();
  }

  @PostMapping("/api/host/standby/ask")
  PeerService.Ask standbyAsk(HttpServletRequest req, @RequestBody UrlBody b) {
    requireStandby(req);
    return run(() -> peers.startAsk(b.url()));
  }

  @GetMapping("/api/host/standby/ask")
  PeerService.Ask standbyAskStatus(HttpServletRequest req) {
    requireStandby(req);
    return peers.ask();
  }

  /** Данные этого компьютера заменятся данными сайта — поэтому только когда сайт не здесь. */
  private void requireStandby(HttpServletRequest req) {
    if (!here(req)) {
      throw ApiException.forbidden("Доступно только в приложении на компьютере хоста");
    }
    if (hosts.serving()) {
      throw ApiException.badRequest("Сайт работает на этом компьютере");
    }
  }

  // ---------- обмен между компьютерами ----------

  /** Другой компьютер просит подключиться — без кода; ответ даст человек здесь. */
  @PostMapping("/api/host/peer/request")
  Map<String, String> request(@RequestBody JoinRequestBody b) {
    return Map.of("secret", run(() -> peers.request(b.computer(), b.name())));
  }

  /** Что ответили на запрос — только тому, у кого секрет запроса. */
  @PostMapping("/api/host/peer/request/answer")
  PeerService.Answer requestAnswer(@RequestBody SecretBody b) {
    return peers.answerFor(b.secret());
  }

  /** Второй компьютер пришёл с кодом — без ключа (ключ он получит здесь). */
  @PostMapping("/api/host/peer/pair")
  Map<String, Object> pair(@RequestBody PairBody b) {
    if (transfers.matches(b.code())) {
      // Коды выглядят одинаково; код переноса увёз бы сайт целиком, а не связал компьютеры.
      throw ApiException.badRequest(
          "Это код переноса сайта, а не код связи. На том компьютере откройте «Управление → Сервер"
              + " → Два компьютера» и нажмите «Показать код»");
    }
    Map<String, Object> p = run(() -> peers.pair(b.code(), b.computer(), b.name()));
    audit.log(null, null, "hosts.peer_add", "instance", null, Map.of("name", nameOf(b.name())));
    return p;
  }

  @GetMapping("/api/host/peer/state")
  Map<String, Object> state(HttpServletRequest req) {
    requirePeer(req);
    requireMain();
    return run(peers::state);
  }

  /** Другой компьютер забирает сайт себе: в ответ — свежий снимок базы. */
  @PostMapping("/api/host/peer/handover")
  void handover(HttpServletRequest req, HttpServletResponse res) throws IOException {
    requirePeer(req);
    requireMain();
    res.setContentType("application/gzip");
    res.setHeader("Cache-Control", "no-store");
    res.setHeader("X-Groupbase-Epoch", String.valueOf(peers.view().epoch()));
    peers.handOver(res.getOutputStream());
  }

  /** «Перенести сайт сюда» — с любого из связанных компьютеров. */
  @PostMapping("/api/host/peers/here")
  PeerService.View moveHere(Actor actor, HttpServletRequest req) {
    requireWindow(actor, req);
    PeerService.View v = run(peers::moveHere);
    audit.log(actor, null, "hosts.peer_here", "instance", null);
    return v;
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

  /** Отвечает другому компьютеру только тот, где сайт: иначе «not_main» — сайт сейчас не здесь. */
  private void requireMain() {
    if (peers.role() != PeerService.Role.MAIN) {
      throw new ApiException(
          HttpStatus.CONFLICT, "not_main", "Сайт сейчас работает не на этом компьютере");
    }
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
