package app.groupbase.sync;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
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

  /**
   * Кто без потока событий спрашивал номер состояния (туннель копит поток) и когда: и они на сайте.
   */
  private final Map<Long, Long> polledAt = new ConcurrentHashMap<>();

  /** Сколько после последнего вопроса человек ещё считается на сайте (спрашивают раз в 3 с). */
  static final long POLL_ONLINE_MS = 15_000;

  /** Кто был на сайте при прошлой проверке: сменился – страницам событие «presence». */
  private volatile Set<Long> lastOnline = Set.of();

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

  synchronized void tick() {
    if (clients.isEmpty()) {
      return;
    }
    Set<Long> now = online();
    if (!now.equals(lastOnline)) {
      lastOnline = Set.copyOf(now);
      clients.forEach(c -> send(c, "presence", "{}"));
    }
    try {
      long seq = maxSeq();
      if (lastSeq < 0) {
        lastSeq = seq;
      } else if (seq != lastSeq) {
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

  /**
   * Номер состояния данных: журнал changes плюс версия данных (её поднимает любое изменение через
   * API – люди, роли, группа, настройки, см. {@link #bump()}). Сравнивается на «не равно»: после
   * снимка базы на копии хоста номер может стать и меньше.
   */
  private long maxSeq() {
    return revision(db);
  }

  /** Номер состояния данных – тот же, что видят страницы и второй компьютер хоста. */
  public static long revision(JdbcClient db) {
    return db.sql(
            "SELECT COALESCE((SELECT MAX(seq) FROM changes), 0)"
                + " + COALESCE((SELECT v FROM data_version WHERE id = 1), 0)")
        .query(Long.class)
        .single();
  }

  /**
   * Что-то изменилось (любой успешный запрос-изменение): страницы узнают сразу – проверка журнала
   * запускается тут же, не дожидаясь своей секунды (1.0.2: комментарии появляются у всех сразу).
   */
  public void bump() {
    try {
      db.sql("UPDATE data_version SET v = v + 1 WHERE id = 1").update();
    } catch (RuntimeException ex) {
      // База занята – изменение всё равно увидят по журналу или следующему изменению.
    }
    ScheduledExecutorService t = timer;
    if (t != null) {
      try {
        t.execute(this::tick);
      } catch (RuntimeException ex) {
        // Сервер останавливается – страницам уже не до обновлений.
      }
    }
  }

  /**
   * Номер состояния без потока событий – для страниц, до которых поток не доходит (туннель копит
   * ответ): они спрашивают его раз в несколько секунд и перечитывают себя, только когда он
   * сменился.
   */
  public long current(long userId) {
    polledAt.put(userId, System.currentTimeMillis());
    return maxSeq();
  }

  /**
   * Кто сейчас на сайте (1.0.2): открыт поток событий или недавно спрашивали номер состояния. Ни
   * IP, ни устройств – только номера людей.
   */
  public Set<Long> online() {
    long since = System.currentTimeMillis() - POLL_ONLINE_MS;
    polledAt.values().removeIf(t -> t < since);
    Set<Long> out = new java.util.HashSet<>(polledAt.keySet());
    clients.forEach(c -> out.add(c.userId()));
    return out;
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
