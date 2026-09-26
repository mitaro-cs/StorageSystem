package app.groupbase.web.api;

import app.groupbase.auth.Actor;
import app.groupbase.sync.LiveUpdates;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Живые обновления (SSE): страница узнаёт об изменениях сразу, данных в потоке нет. */
@RestController
class LiveController {

  private final LiveUpdates live;

  LiveController(LiveUpdates live) {
    this.live = live;
  }

  @GetMapping(value = "/api/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter live(Actor actor, HttpServletResponse res) {
    // Прокси и туннели не должны копить поток: события нужны сразу.
    res.setHeader("X-Accel-Buffering", "no");
    res.setHeader("Cache-Control", "no-cache, no-transform");
    return live.subscribe(actor.id());
  }
}
