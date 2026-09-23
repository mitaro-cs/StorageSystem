package app.groupbase.web.api;

import app.groupbase.auth.Actor;
import app.groupbase.sync.SyncService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Изменения для офлайн-копии на устройстве (см. SyncService). */
@RestController
class SyncController {

  private final SyncService sync;

  SyncController(SyncService sync) {
    this.sync = sync;
  }

  @GetMapping("/api/sync")
  SyncService.Result sync(Actor actor, @RequestParam(defaultValue = "0") long after) {
    return sync.sync(actor, after);
  }
}
