package app.groupbase.web.api;

import app.groupbase.accounts.GroupService;
import app.groupbase.accounts.SetupService;
import app.groupbase.backup.RestoreStager;
import app.groupbase.web.ApiException;
import app.groupbase.web.Public;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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

  private final SetupService setup;
  private final Http http;
  private final RestoreStager restore;

  SetupController(SetupService setup, Http http, RestoreStager restore) {
    this.setup = setup;
    this.http = http;
    this.restore = restore;
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
