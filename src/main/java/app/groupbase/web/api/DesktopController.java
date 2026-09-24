package app.groupbase.web.api;

import app.groupbase.accounts.SetupService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Permission;
import app.groupbase.backup.BackupService;
import app.groupbase.config.GroupbaseProperties;
import app.groupbase.desktop.DesktopBridge;
import app.groupbase.desktop.LocalOpen;
import app.groupbase.store.UserStore;
import app.groupbase.web.ApiException;
import app.groupbase.web.Public;
import app.groupbase.web.Requests;
import app.groupbase.web.Require;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Только для приложения хоста: вход окна приложения и действия на его компьютере. */
@RestController
@RequestMapping("/api/desktop")
class DesktopController {

  record OpenBody(String what) {}

  private final DesktopBridge bridge;
  private final SetupService setup;
  private final UserStore users;
  private final Http http;
  private final BackupService backups;
  private final GroupbaseProperties props;

  DesktopController(
      DesktopBridge bridge,
      SetupService setup,
      UserStore users,
      Http http,
      BackupService backups,
      GroupbaseProperties props) {
    this.bridge = bridge;
    this.setup = setup;
    this.users = users;
    this.http = http;
    this.backups = backups;
    this.props = props;
  }

  /**
   * Одноразовая ссылка от оболочки: окно приложения входит как хост без пароля. Ссылку знает только
   * оболочка, и принимается она только с этого же компьютера, не через туннель.
   */
  @Public
  @GetMapping("/enter")
  ResponseEntity<Void> enter(
      @RequestParam("t") String token, HttpServletRequest req, HttpServletResponse res) {
    if (!Requests.fromThisComputer(req) || !bridge.consumeEnterToken(token)) {
      return redirect("/login");
    }
    if (setup.needed()) {
      return redirect("/setup?code=" + setup.setupCode());
    }
    return users
        .firstAdmin()
        .map(
            host -> {
              http.startSession(host, res);
              return redirect("/");
            })
        .orElseGet(() -> redirect("/login"));
  }

  /** «Обновить сейчас»: оболочка скачает новую версию, остановит сервер и перезапустится. */
  @Require(Permission.MANAGE_INSTANCE)
  @PostMapping("/update")
  Map<String, String> update(Actor actor) {
    if (!bridge.enabled() || !actor.local() || bridge.availableUpdate() == null) {
      throw ApiException.forbidden("Обновление запускается в приложении на компьютере хоста");
    }
    bridge.requestUpdate();
    return Map.of("status", "updating");
  }

  /** Открыть на компьютере хоста папку с данными, копиями или журналами. */
  @Require(Permission.MANAGE_INSTANCE)
  @PostMapping("/open")
  Map<String, String> open(Actor actor, @RequestBody OpenBody b) throws IOException {
    if (!bridge.enabled() || !actor.local()) {
      throw ApiException.forbidden("Доступно только в приложении на компьютере хоста");
    }
    Path dir =
        switch (b.what() == null ? "" : b.what()) {
          case "data" -> props.dataDir();
          case "backups" -> backups.dir();
          case "logs" -> props.dataDir().resolve("logs");
          default -> throw ApiException.badRequest("Неизвестная папка");
        };
    Files.createDirectories(dir);
    LocalOpen.folder(dir.toAbsolutePath());
    return Map.of("status", "ok");
  }

  private static ResponseEntity<Void> redirect(String path) {
    return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(path)).build();
  }
}
