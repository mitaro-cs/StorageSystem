package app.groupbase.web.api;

import app.groupbase.auth.Actor;
import app.groupbase.store.Person;
import app.groupbase.sync.LiveUpdates;
import app.groupbase.sync.Presence;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Живые обновления (SSE): страница узнаёт об изменениях сразу, данных в потоке нет. */
@RestController
class LiveController {

  private final LiveUpdates live;
  private final Presence presence;

  LiveController(LiveUpdates live, Presence presence) {
    this.live = live;
    this.presence = presence;
  }

  /** Кто сейчас на сайте – из тех, кого человек и так видит в своих группах (1.0.2). */
  @GetMapping("/api/presence")
  Map<String, List<Person>> presence(Actor actor) {
    return Map.of("online", presence.online(actor));
  }

  @GetMapping(value = "/api/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter live(Actor actor, HttpServletResponse res) {
    // Прокси и туннели не должны копить поток: события нужны сразу.
    res.setHeader("X-Accel-Buffering", "no");
    res.setHeader("Cache-Control", "no-cache, no-transform");
    return live.subscribe(actor.id());
  }

  /** Номер состояния одним коротким ответом – запасной путь, когда поток не доходит. */
  @GetMapping("/api/live/seq")
  Map<String, Long> seq(Actor actor, HttpServletResponse res) {
    res.setHeader("Cache-Control", "no-store");
    return Map.of("seq", live.current(actor.id()));
  }
}
