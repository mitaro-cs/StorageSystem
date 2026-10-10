package app.groupbase.status;

import app.groupbase.accounts.PublicUrl;
import app.groupbase.config.GroupbaseProperties;
import app.groupbase.sync.LiveUpdates;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Мониторинг хоста (1.0.2): за последние сутки по минутам – запросы, ошибки сервера, время ответа
 * (p50 и p95 по гистограмме), проверка адреса сайта снаружи (через туннель: дошёл ли ответ и за
 * сколько) и сколько людей на сайте. Минуты, когда сервер не работал, остаются пустыми – это и есть
 * простой. История переживает перезапуск: раз в 5 минут и при остановке – в {@code monitor.json}.
 * Ни адресов, ни имён – только числа.
 */
@Service
public class Monitor implements SmartLifecycle {

  static final int MINUTES = 24 * 60;
  static final long MINUTE = 60_000;

  /** Верхние границы корзин времени ответа, мс; последняя – всё, что дольше. */
  static final int[] BOUNDS = {5, 10, 20, 40, 80, 160, 320, 640, 1280, 2560, 5120};

  /** Одна минута. Поля – публичные для JSON-файла истории. */
  static final class Minute {
    public long t;
    public int requests;
    public int errors;
    public int[] hist = new int[BOUNDS.length + 1];
    public int probes;
    public int probeFails;
    public long rttSum;
    public int online;
    public boolean up;
  }

  /** Точка графика: минута или несколько, сложенные вместе. */
  public record Point(
      long t,
      int requests,
      int errors,
      Integer p50,
      Integer p95,
      Integer rtt,
      Double reach,
      int online,
      double up) {}

  /** Что сейчас – для плиток. */
  public record Now(
      int online,
      double requestsPerMin,
      Integer p50,
      Integer p95,
      int errorsHour,
      Integer rtt,
      Boolean reachable,
      Long probedAt,
      String url) {}

  public record Memory(long used, long max) {}

  /**
   * @param uptimeDay доля минут суток, когда сайт работал и был доступен (null – данных ещё нет)
   * @param step шаг точек, мс
   */
  public record View(
      Now now,
      Double uptimeDay,
      Double uptimeHour,
      long startedAt,
      Memory memory,
      long step,
      List<Point> points) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Clock clock;
  private final Path file;
  private final LiveUpdates live;
  private final Supplier<Optional<String>> publicUrl;
  private final Minute[] ring = new Minute[MINUTES];
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();

  private volatile Integer lastRtt;
  private volatile Boolean lastReach;
  private volatile Long lastProbe;
  private ScheduledExecutorService timer;
  private volatile boolean running;

  @Autowired
  public Monitor(GroupbaseProperties props, Clock clock, LiveUpdates live, PublicUrl publicUrl) {
    this(clock, props.dataDir().resolve("monitor.json"), live, publicUrl::get);
  }

  Monitor(Clock clock, Path file, LiveUpdates live, Supplier<Optional<String>> publicUrl) {
    this.clock = clock;
    this.file = file;
    this.live = live;
    this.publicUrl = publicUrl;
  }

  /**
   * Минута по времени – создаётся при первом обращении (прежняя на этом месте – суточной давности).
   */
  private Minute at(long millis) {
    long t = millis / MINUTE * MINUTE;
    int i = (int) ((t / MINUTE) % MINUTES);
    Minute m = ring[i];
    if (m == null || m.t != t) {
      m = new Minute();
      m.t = t;
      ring[i] = m;
    }
    return m;
  }

  /** Запрос к API закончился (MonitorFilter). */
  public synchronized void request(long millis, int status) {
    Minute m = at(clock.millis());
    m.up = true;
    m.requests++;
    if (status >= 500) {
      m.errors++;
    }
    m.hist[bucket(millis)]++;
  }

  static int bucket(long millis) {
    for (int i = 0; i < BOUNDS.length; i++) {
      if (millis <= BOUNDS[i]) {
        return i;
      }
    }
    return BOUNDS.length;
  }

  /** Раз в минуту: сервер жив, сколько людей на сайте, проверка адреса снаружи. */
  void tick() {
    int online = live.online().size();
    synchronized (this) {
      Minute m = at(clock.millis());
      m.up = true;
      m.online = Math.max(m.online, online);
    }
    Optional<String> url = publicUrl.get();
    if (url.isPresent()) {
      probe(url.get());
    }
    if ((clock.millis() / MINUTE) % 5 == 0) {
      save();
    }
  }

  /**
   * Адрес сайта снаружи – тем же путём, что идут телефоны группы (туннель): ответил ли сам сервер
   * группы (метка X-Groupbase или JSON – страница туннеля отвечает HTML) и за сколько.
   */
  void probe(String url) {
    long start = System.nanoTime();
    boolean ok;
    try {
      HttpResponse<Void> r =
          http.send(
              HttpRequest.newBuilder(URI.create(url.replaceAll("/+$", "") + "/api/health"))
                  .timeout(Duration.ofSeconds(10))
                  .header("Cache-Control", "no-store")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      ok =
          r.statusCode() == 200
              && (r.headers().firstValue("X-Groupbase").isPresent()
                  || r.headers().firstValue("Content-Type").orElse("").contains("json"));
    } catch (IOException | IllegalArgumentException ex) {
      ok = false;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return;
    }
    probed(ok, (int) ((System.nanoTime() - start) / 1_000_000));
  }

  synchronized void probed(boolean ok, int ms) {
    Minute m = at(clock.millis());
    m.probes++;
    if (ok) {
      m.rttSum += ms;
    } else {
      m.probeFails++;
    }
    lastReach = ok;
    lastRtt = ok ? ms : null;
    lastProbe = clock.millis();
  }

  /** Последние {@code minutes} минут точками по {@code perPoint} минут. */
  public synchronized View view(int minutes, int perPoint) {
    long now = clock.millis() / MINUTE * MINUTE;
    long from = now - (long) (minutes - 1) * MINUTE;
    List<Point> points = new ArrayList<>();
    for (long t = from; t <= now; t += perPoint * MINUTE) {
      List<Minute> group = new ArrayList<>();
      for (int k = 0; k < perPoint; k++) {
        Minute m = existing(t + k * MINUTE);
        if (m != null) {
          group.add(m);
        }
      }
      points.add(point(t, group, perPoint));
    }
    Minute last15 = sum(range(now - 14 * MINUTE, now));
    List<Minute> last5 = range(now - 4 * MINUTE, now);
    int requests5 = last5.stream().mapToInt(m -> m.requests).sum();
    int errorsHour = range(now - 59 * MINUTE, now).stream().mapToInt(m -> m.errors).sum();
    Now n =
        new Now(
            live.online().size(),
            Math.round(requests5 / 5.0 * 10) / 10.0,
            percentile(last15.hist, 0.5),
            percentile(last15.hist, 0.95),
            errorsHour,
            lastRtt,
            lastReach,
            lastProbe,
            publicUrl.get().orElse(null));
    var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    return new View(
        n,
        uptime(now - (MINUTES - 1) * MINUTE, now),
        uptime(now - 59 * MINUTE, now),
        ManagementFactory.getRuntimeMXBean().getStartTime(),
        new Memory(heap.getUsed(), heap.getMax()),
        perPoint * MINUTE,
        points);
  }

  private Minute existing(long t) {
    Minute m = ring[(int) ((t / MINUTE) % MINUTES)];
    return m != null && m.t == t ? m : null;
  }

  private List<Minute> range(long from, long to) {
    List<Minute> out = new ArrayList<>();
    for (long t = from; t <= to; t += MINUTE) {
      Minute m = existing(t);
      if (m != null) {
        out.add(m);
      }
    }
    return out;
  }

  private static Minute sum(List<Minute> list) {
    Minute s = new Minute();
    for (Minute m : list) {
      s.requests += m.requests;
      s.errors += m.errors;
      for (int i = 0; i < s.hist.length; i++) {
        s.hist[i] += m.hist[i];
      }
      s.probes += m.probes;
      s.probeFails += m.probeFails;
      s.rttSum += m.rttSum;
      s.online = Math.max(s.online, m.online);
      s.up |= m.up;
    }
    return s;
  }

  private static Point point(long t, List<Minute> group, int perPoint) {
    Minute s = sum(group);
    int okProbes = s.probes - s.probeFails;
    long upMinutes = group.stream().filter(m -> m.up).count();
    return new Point(
        t,
        s.requests,
        s.errors,
        percentile(s.hist, 0.5),
        percentile(s.hist, 0.95),
        okProbes > 0 ? (int) (s.rttSum / okProbes) : null,
        s.probes > 0 ? (double) okProbes / s.probes : null,
        s.online,
        (double) upMinutes / perPoint);
  }

  /**
   * Перцентиль по гистограмме: верхняя граница корзины, в которую он попал, – оценка «не дольше»
   * (null – запросов не было).
   */
  static Integer percentile(int[] hist, double q) {
    long total = 0;
    for (int c : hist) {
      total += c;
    }
    if (total == 0) {
      return null;
    }
    long need = (long) Math.ceil(q * total);
    long seen = 0;
    for (int i = 0; i < hist.length; i++) {
      seen += hist[i];
      if (seen >= need) {
        return i < BOUNDS.length ? BOUNDS[i] : BOUNDS[BOUNDS.length - 1] * 2;
      }
    }
    return BOUNDS[BOUNDS.length - 1] * 2;
  }

  /**
   * Доля минут, когда сайт работал: сервер жив, и, если адрес сайта проверяли, он отвечал. Минуты
   * до первого запуска мониторинга не считаются.
   */
  private Double uptime(long from, long to) {
    long first = Long.MAX_VALUE;
    for (Minute m : ring) {
      if (m != null && m.t >= from && m.t <= to) {
        first = Math.min(first, m.t);
      }
    }
    if (first == Long.MAX_VALUE) {
      return null;
    }
    int total = 0;
    int good = 0;
    for (long t = first; t <= to; t += MINUTE) {
      total++;
      Minute m = existing(t);
      if (m != null && m.up && (m.probes == 0 || m.probeFails < m.probes)) {
        good++;
      }
    }
    return total == 0 ? null : (double) good / total;
  }

  synchronized void save() {
    List<Minute> list = new ArrayList<>();
    for (Minute m : ring) {
      if (m != null) {
        list.add(m);
      }
    }
    try {
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      JSON.writeValue(tmp.toFile(), list);
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException | RuntimeException ex) {
      // История – не главное: не записалась – начнём заново после перезапуска.
    }
  }

  synchronized void load() {
    if (!Files.exists(file)) {
      return;
    }
    try {
      Minute[] list = JSON.readValue(file.toFile(), Minute[].class);
      long oldest = clock.millis() - MINUTES * MINUTE;
      for (Minute m : list) {
        if (m.t > oldest && m.hist != null && m.hist.length == BOUNDS.length + 1) {
          ring[(int) ((m.t / MINUTE) % MINUTES)] = m;
        }
      }
    } catch (RuntimeException ex) {
      // Испорченный файл – начинаем с чистого листа.
    }
  }

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    load();
    timer =
        Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("monitor").factory());
    long untilMinute = MINUTE - clock.millis() % MINUTE;
    // Сразу после запуска – отметка «сервер жив», дальше – в начале каждой минуты.
    timer.schedule(this::safeTick, 2, TimeUnit.SECONDS);
    timer.scheduleAtFixedRate(this::safeTick, untilMinute + 1_000, MINUTE, TimeUnit.MILLISECONDS);
    running = true;
  }

  private void safeTick() {
    try {
      tick();
    } catch (RuntimeException ex) {
      // Минуту пропустим – следующая будет.
    }
  }

  @Override
  public synchronized void stop() {
    running = false;
    if (timer != null) {
      timer.shutdownNow();
      timer = null;
    }
    save();
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
