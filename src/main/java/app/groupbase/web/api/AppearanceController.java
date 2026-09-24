package app.groupbase.web.api;

import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Permission;
import app.groupbase.avatars.LoginBackground;
import app.groupbase.web.ApiException;
import app.groupbase.web.Public;
import app.groupbase.web.Require;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Внешний вид страниц входа: фон виден и без входа, меняет его администратор. */
@RestController
class AppearanceController {

  record Body(String loginBackground) {}

  private final LoginBackground background;
  private final AuditService audit;

  AppearanceController(LoginBackground background, AuditService audit) {
    this.background = background;
    this.audit = audit;
  }

  @Public
  @GetMapping("/api/appearance")
  Map<String, String> get() {
    return Map.of("loginBackground", background.current());
  }

  @Public
  @GetMapping("/api/appearance/background/{id}.webp")
  void image(@PathVariable String id, HttpServletResponse res) throws IOException {
    try (InputStream in = background.open(id)) {
      if (in == null) {
        throw ApiException.notFound();
      }
      res.setContentType("image/webp");
      // Идентификатор меняется с каждой новой картинкой — кешировать можно навсегда.
      res.setHeader(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable");
      try (OutputStream out = res.getOutputStream()) {
        in.transferTo(out);
      }
    }
  }

  @Require(Permission.MANAGE_INSTANCE)
  @PutMapping("/api/admin/appearance")
  Map<String, String> choose(Actor actor, @RequestBody Body b) {
    String v = background.choose(b.loginBackground());
    audit.log(actor, null, "appearance.update", "instance", null, Map.of("background", v));
    return Map.of("loginBackground", v);
  }

  @Require(Permission.MANAGE_INSTANCE)
  @PutMapping("/api/admin/appearance/background")
  Map<String, String> upload(Actor actor, HttpServletRequest req) throws IOException {
    String v = background.upload(req.getInputStream());
    audit.log(actor, null, "appearance.update", "instance", null, Map.of("background", "image"));
    return Map.of("loginBackground", v);
  }

  @Require(Permission.MANAGE_INSTANCE)
  @DeleteMapping("/api/admin/appearance/background")
  Map<String, String> reset(Actor actor) {
    String v = background.choose(LoginBackground.DEFAULT);
    audit.log(actor, null, "appearance.update", "instance", null, Map.of("background", v));
    return Map.of("loginBackground", v);
  }
}
