package app.groupbase.sync;

import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Живые обновления: открытые страницы сразу узнают, что на сервере что-то изменилось (новый
 * комментарий, новость, задание, материал), и перечитывают себя — без перезагрузки. Сервер сообщает
 * только номер последнего изменения из журнала синхронизации (SSE), данные страница берёт обычными
 * запросами с проверкой прав. Колокольчик — отдельным событием тем, кому пришло уведомление.
 */
@Service
public class LiveUpdates implements DisposableBean, SmartLifecycle {

  /** Пустая строка раз в 25 секунд: прокси и туннели не закрывают молчащее соединение. */
  static final long PING_MS = 25_000;

  private record Client(long userId, SseEmitter emitter) {}

  private final JdbcClient db;
  private final Set<Client> clients = ConcurrentHashMap.newKeySet();

  /** Проверка журнала раз в секунду — пока компонент запущен (start/stop, см. ниже). */
  private ScheduledExecutorService timer;

  /** Номер последнего изменения; -1 — ещё не спрашивали базу (при запуске её готовит Flyway). */
  private volatile long lastSeq = -1;

  private volatile long lastPing = System.currentTimeMillis();

  public LiveUpdates(JdbcClient db) {
    this.db = db;
  }

  /** Подписка страницы: сразу «hello» с номером — по нему видно, что поток доходит. */
  public SseEmitter subscribe(long userId) {
    if (lastSeq < 0) {
      lastSeq = maxSeq();
    }
    SseEmitter emitter = new SseEmitter(0L);
    Client c = new Client(userId, emitter);
    clients.add(c);
    emitter.onCompletion(() -> clients.remove(c));
    emitter.onTimeout(() -> clients.remove(c));
    emitter.onError(ex -> clients.remove(c));
    send(c, "hello", seq(lastSeq));
    return emitter;
  }

  /** Новые уведомления — этим людям: колокольчик обновится сразу. */
  public void bell(Collection<Long> userIds) {
    for (Client c : clients) {
      if (userIds.contains(c.userId())) {
        send(c, "bell", "{}");
      }
    }
  }

  int clients() {
    return clients.size();
  }

  void tick() {
    if (clients.isEmpty()) {
      return;
    }
    try {
      long seq = maxSeq();
      if (lastSeq < 0) {
        lastSeq = seq;
      } else if (seq > lastSeq) {
        lastSeq = seq;
        clients.forEach(c -> send(c, "change", seq(seq)));
      } else if (System.currentTimeMillis() - lastPing > PING_MS) {
        lastPing = System.currentTimeMillis();
        clients.forEach(this::ping);
      }
    } catch (RuntimeException ex) {
      // База занята записью — проверим через секунду.
    }
  }

  private long maxSeq() {
    return db.sql("SELECT COALESCE(MAX(seq), 0) FROM changes").query(Long.class).single();
  }

  private static String seq(long seq) {
    return "{\"seq\":" + seq + "}";
  }

  /** Одно соединение пишут два потока (изменения и уведомления) — по очереди. */
  private void send(Client c, String name, String data) {
    synchronized (c) {
      try {
        c.emitter().send(SseEmitter.event().name(name).data(data));
      } catch (IOException | IllegalStateException ex) {
        drop(c);
      }
    }
  }

  private void ping(Client c) {
    synchronized (c) {
      try {
        c.emitter().send(SseEmitter.event().comment("ping"));
      } catch (IOException | IllegalStateException ex) {
        drop(c);
      }
    }
  }

  private void drop(Client c) {
    clients.remove(c);
    try {
      c.emitter().complete();
    } catch (RuntimeException ignored) {
      // Соединения уже нет.
    }
  }

  private volatile boolean running;

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    timer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("live").factory());
    timer.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    running = true;
  }

  /**
   * Остановка сервера: соединения закрываются первыми (фаза выше, чем у «вежливой» остановки
   * веб-сервера) — иначе он ждал бы открытые страницы до 20 секунд, и перезапуск и обновление
   * приложения хоста затягивались бы. После start() всё работает снова.
   */
  @Override
  public synchronized void stop() {
    running = false;
    if (timer != null) {
      timer.shutdownNow();
      timer = null;
    }
    clients.forEach(this::drop);
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public void destroy() {
    stop();
  }
}
