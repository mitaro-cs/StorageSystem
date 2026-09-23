package app.groupbase.web;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HealthController {

  @GetMapping("/api/health")
  Map<String, String> health() {
    return Map.of("status", "ok");
  }

  /** Сайт группы не должен попадать в поисковики. */
  @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
  String robots() {
    return "User-agent: *\nDisallow: /\n";
  }
}
