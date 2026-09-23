package app.groupbase.web.api;

import app.groupbase.accounts.GroupService;
import app.groupbase.accounts.SetupService;
import app.groupbase.web.Public;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
      String password,
      Boolean adminIsHeadman) {}

  private final SetupService setup;
  private final Http http;

  SetupController(SetupService setup, Http http) {
    this.setup = setup;
    this.http = http;
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
                b.password(),
                Boolean.TRUE.equals(b.adminIsHeadman())));
    return http.startSession(user, res);
  }
}
