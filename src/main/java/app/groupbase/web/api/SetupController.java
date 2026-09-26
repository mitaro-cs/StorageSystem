package app.groupbase.web.api;

import app.groupbase.accounts.GroupService;
import app.groupbase.accounts.SetupService;
import app.groupbase.backup.RestoreStager;
import app.groupbase.hosts.HostService;
import app.groupbase.web.ApiException;
import app.groupbase.web.Public;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/setup")
@Public
class SetupController {

  record SetupBody(
      String code,
      String mode,
      String instanceName,
      GroupService.GroupInput group,
      String username,
      String displayName,
      String password) {}

  record JoinBody(String path) {}

  private final SetupService setup;
  private final Http http;
  private final RestoreStager restore;
  private final HostService hosts;

  SetupController(SetupService setup, Http http, RestoreStager restore, HostService hosts) {
    this.setup = setup;
    this.http = http;
    this.restore = restore;
    this.hosts = hosts;
  }

  /**
   * Первый запуск на втором компьютере хоста: сайты, которые уже лежат в облачных папках этого
   * компьютера (их сохранил туда другой компьютер).
   */
  @GetMapping("/sites")
  List<HostService.Found> sites(@RequestParam String code) {
    setup.checkCode(code);
    if (!setup.needed()) {
      throw ApiException.conflict("already_setup", "Сайт уже настроен");
    }
    return hosts.sites();
  }

  /** Подключить этот компьютер к сайту из облачной папки: данные возьмутся при перезапуске. */
  @PostMapping("/join")
  RestoreStager.Result join(@RequestParam String code, @RequestBody JoinBody b) {
    setup.checkCode(code);
    if (!setup.needed()) {
      throw ApiException.conflict("already_setup", "Сайт уже настроен");
    }
    try {
      String name = hosts.join(b.path());
      return new RestoreStager.Result(
          "restarting", "Берём данные сайта «" + name + "» и перезапускаемся…");
    } catch (HostService.Problem e) {
      throw ApiException.badRequest(e.getMessage());
    }
  }

  /**
   * Первый запуск на новом компьютере: вместо настройки — восстановить всё из резервной копии.
   * Только с кодом первичной настройки и только пока инстанс пуст.
   */
  @PutMapping("/restore")
  RestoreStager.Result restore(@RequestParam String code, HttpServletRequest req)
      throws IOException {
    setup.checkCode(code);
    if (!setup.needed()) {
      throw ApiException.conflict("already_setup", "Сайт уже настроен");
    }
    try {
      return restore.fromUpload(req.getInputStream());
    } catch (IOException e) {
      throw ApiException.badRequest(e.getMessage());
    }
  }

  @GetMapping
  Map<String, Boolean> status() {
    return Map.of("needed", setup.needed());
  }

  @PostMapping
  Map<String, String> run(@RequestBody SetupBody b, HttpServletResponse res) {
    var user =
        setup.setupWithCode(
            b.code(),
            new SetupService.Request(
                b.mode(),
                b.instanceName(),
                b.group(),
                b.username(),
                b.displayName(),
                b.password()));
    return http.startSession(user, res);
  }
}
