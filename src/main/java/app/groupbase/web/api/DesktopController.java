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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

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
   *
   * <p>Ответ — страница, а не перенаправление: service worker старых версий получал перенаправление
   * «непрозрачным», не видел метки сервера и подставлял сохранённую страницу — окно бесконечно
   * возвращалось сюда же. Страница подключает {@code /host-window.js}: он убирает из окна хоста
   * service worker и копию данных (они там не нужны — сервер на этом же компьютере) и переходит
   * дальше.
   */
  @Public
  @GetMapping(value = "/enter", produces = MediaType.TEXT_HTML_VALUE)
  ResponseEntity<String> enter(
      @RequestParam("t") String token, HttpServletRequest req, HttpServletResponse res) {
    // Уборка в окне — только на компьютере хоста: чужая ссылка на этот адрес не должна стирать
    // участнику копию данных с ещё не отправленными действиями.
    boolean host = bridge.enabled() && Requests.fromThisComputer(req);
    if (!host || !bridge.consumeEnterToken(token)) {
      return page("/login", host);
    }
    if (setup.needed()) {
      return page("/setup?code=" + setup.setupCode(), true);
    }
    return users
        .firstAdmin()
        .map(
            h -> {
              http.startSession(h, res);
              return page("/", true);
            })
        .orElseGet(() -> page("/login", true));
  }

  /**
   * Переход дальше. В окне хоста — скриптом после уборки (без скрипта — через две секунды), в
   * остальных случаях — сразу.
   */
  static ResponseEntity<String> page(String next, boolean hostWindow) {
    String url = HtmlUtils.htmlEscape(next);
    String html =
        """
        <!doctype html>
        <html lang="ru">
        <head>
        <meta charset="utf-8">
        <meta name="color-scheme" content="light dark">
        <meta http-equiv="refresh" content="%s;url=%s">
        <title>groupbase</title>
        %s
        </head>
        <body></body>
        </html>
        """
            .formatted(
                hostWindow ? "2" : "0",
                url,
                hostWindow
                    ? "<script src=\"/host-window.js\" data-next=\"" + url + "\"></script>"
                    : "");
    return ResponseEntity.ok()
        .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
        .cacheControl(CacheControl.noStore())
        .body(html);
  }

  /**
   * «Обновить сейчас»: оболочка сама проверит версию, скачает её, остановит сервер, установит и
   * перезапустится. Ей не нужно было находить обновление заранее — хватит того, что его нашёл
   * сервер.
   */
  @Require(Permission.MANAGE_INSTANCE)
  @PostMapping("/update")
  Map<String, String> update(Actor actor) {
    if (!bridge.enabled() || !actor.local()) {
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
}
