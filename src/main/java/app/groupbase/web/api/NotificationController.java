package app.groupbase.web.api;

import app.groupbase.auth.Actor;
import app.groupbase.notify.NotificationPrefs;
import app.groupbase.notify.NotificationPrefs.Prefs;
import app.groupbase.notify.NotificationStore;
import app.groupbase.notify.PushCrypto;
import app.groupbase.notify.PushSender;
import app.groupbase.notify.PushSubscriptions;
import app.groupbase.notify.VapidKeys;
import app.groupbase.web.ApiException;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Колокольчик, настройки уведомлений и подписки устройств на push. */
@RestController
class NotificationController {

  record Page(List<NotificationStore.Notification> items, int unread, Long next) {}

  record ReadBody(List<Long> ids, Boolean all) {}

  record SubscribeBody(String endpoint, String p256dh, String auth, String device) {}

  record EndpointBody(String endpoint) {}

  record PrefsBody(
      Boolean homework,
      String news,
      Boolean materials,
      Boolean reminders,
      Boolean digest,
      Integer digestAt) {}

  record Settings(
      Prefs prefs, List<PushSubscriptions.Device> devices, boolean pushEnabled, String publicKey) {}

  private static final Set<String> NEWS = Set.of("all", "urgent", "none");

  private final NotificationStore store;
  private final NotificationPrefs prefs;
  private final PushSubscriptions subs;
  private final PushSender push;
  private final VapidKeys keys;
  private final Clock clock;

  NotificationController(
      NotificationStore store,
      NotificationPrefs prefs,
      PushSubscriptions subs,
      PushSender push,
      VapidKeys keys,
      Clock clock) {
    this.store = store;
    this.prefs = prefs;
    this.subs = subs;
    this.push = push;
    this.keys = keys;
    this.clock = clock;
  }

  @GetMapping("/api/notifications")
  Page list(
      Actor actor,
      @RequestParam(required = false) Long before,
      @RequestParam(defaultValue = "30") int limit) {
    int n = Math.clamp(limit, 1, 50);
    var items = store.list(actor.id(), before, n + 1);
    Long next = null;
    if (items.size() > n) {
      items = items.subList(0, n);
      next = items.getLast().id();
    }
    return new Page(items, store.unread(actor.id()), next);
  }

  @GetMapping("/api/notifications/unread")
  Map<String, Integer> unread(Actor actor) {
    return Map.of("count", store.unread(actor.id()));
  }

  @PostMapping("/api/notifications/read")
  Map<String, Integer> read(Actor actor, @RequestBody ReadBody b) {
    if (Boolean.TRUE.equals(b.all())) {
      store.markAllRead(actor.id(), clock.millis());
    } else if (b.ids() != null) {
      store.markRead(actor.id(), b.ids().stream().limit(200).toList(), clock.millis());
    }
    return Map.of("count", store.unread(actor.id()));
  }

  @GetMapping("/api/me/notifications")
  Settings settings(Actor actor) {
    return new Settings(
        prefs.get(actor.id()), subs.devices(actor.id()), push.enabled(), keys.publicKey());
  }

  @PutMapping("/api/me/notifications")
  Settings save(Actor actor, @RequestBody PrefsBody b) {
    Prefs cur = prefs.get(actor.id());
    String news = b.news() == null ? cur.news() : b.news();
    if (!NEWS.contains(news)) {
      throw ApiException.invalid("news", "Новости: all, urgent или none");
    }
    int at = b.digestAt() == null ? cur.digestAt() : b.digestAt();
    if (at < 0 || at > 1439) {
      throw ApiException.invalid("digestAt", "Время сводки — от 00:00 до 23:59");
    }
    prefs.save(
        actor.id(),
        new Prefs(
            b.homework() == null ? cur.homework() : b.homework(),
            news,
            b.materials() == null ? cur.materials() : b.materials(),
            b.reminders() == null ? cur.reminders() : b.reminders(),
            b.digest() == null ? cur.digest() : b.digest(),
            at));
    return settings(actor);
  }

  @PostMapping("/api/push/subscriptions")
  Map<String, String> subscribe(Actor actor, @RequestBody SubscribeBody b) {
    if (!push.enabled()) {
      throw new ApiException(
          HttpStatus.CONFLICT, "push_disabled", "Push-уведомления отключены администратором");
    }
    URI uri;
    try {
      uri = URI.create(b.endpoint() == null ? "" : b.endpoint().strip());
    } catch (IllegalArgumentException e) {
      throw ApiException.invalid("endpoint", "Неверный адрес подписки");
    }
    if (b.endpoint().length() > 2000 || !push.allowed(uri)) {
      throw ApiException.invalid(
          "endpoint", "Этот браузер использует службу уведомлений, которую сервер не поддерживает");
    }
    try {
      PushCrypto.publicKey(PushCrypto.unb64(b.p256dh()));
      if (PushCrypto.unb64(b.auth()).length != 16) {
        throw new IllegalArgumentException();
      }
    } catch (RuntimeException e) {
      throw ApiException.invalid("p256dh", "Неверные ключи подписки");
    }
    String device = b.device() == null ? "" : b.device().strip();
    subs.save(
        actor.id(),
        uri.toString(),
        b.p256dh().strip(),
        b.auth().strip(),
        device.length() > 60 ? device.substring(0, 60) : device,
        clock.millis());
    return Map.of("status", "ok");
  }

  @DeleteMapping("/api/push/subscriptions")
  Map<String, String> unsubscribe(Actor actor, @RequestBody EndpointBody b) {
    if (b.endpoint() != null) {
      subs.delete(actor.id(), b.endpoint().strip());
    }
    return Map.of("status", "ok");
  }

  @DeleteMapping("/api/push/devices/{id}")
  Map<String, String> removeDevice(Actor actor, @PathVariable long id) {
    if (!subs.deleteDevice(actor.id(), id)) {
      throw ApiException.notFound();
    }
    return Map.of("status", "ok");
  }

  /** Пробное уведомление на все устройства пользователя. */
  @PostMapping("/api/push/test")
  Map<String, Integer> test(Actor actor) {
    var mine = subs.forUsers(List.of(actor.id()));
    int delivered =
        push.sendNow(
            mine,
            new PushSender.Message(
                "test",
                "Уведомления работают",
                "Так будут приходить новые задания и напоминания о сроках",
                "/notifications",
                false));
    return Map.of("devices", mine.size(), "delivered", delivered);
  }
}
