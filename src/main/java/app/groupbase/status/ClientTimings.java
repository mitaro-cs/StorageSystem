package app.groupbase.status;

import app.groupbase.config.GroupbaseProperties;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Скорость сайта глазами группы (1.0.2): браузеры сами меряют, за сколько приходят ответы API и
 * открывается страница, и раз в несколько минут присылают замеры с видом устройства, сети и пути
 * (через туннель или в локальной сети). Здесь – сутки по часам; кто прислал – только число людей.
 * История переживает перезапуск ({@code timings.json}).
 */
@Component
public class ClientTimings {

  /** Границы корзин времени ответа API, мс (как у мониторинга сервера). */
  static final int[] API = Monitor.BOUNDS;

  /** Границы корзин открытия страницы, мс. */
  static final int[] LOAD = {250, 500, 1000, 2000, 3000, 5000, 8000, 13000, 20000};

  static final int HOURS = 24;
  static final long HOUR = 3_600_000;
  static final int MAX_SAMPLES = 100;

  /** Не чаще раза в минуту от человека – остальное отбрасывается. */
  static final long MIN_GAP = 50_000;

  static final Set<String> DEVICES = Set.of("phone", "tablet", "computer");
  static final Set<String> NETS = Set.of("wifi", "cellular", "ethernet", "other", "unknown");
  static final Set<String> VIA = Set.of("tunnel", "local");

  /** Замер от браузера: время ответов API с прошлого раза и (раз за открытие) загрузка страницы. */
  public record Report(String device, String net, String via, List<Integer> api, Integer load) {}

  /** Строка таблицы: устройство × сеть × путь. */
  public record Row(
      String device,
      String net,
      String via,
      int people,
      int samples,
      Integer apiP50,
      Integer apiP95,
      Integer loadP50,
      long lastAt) {}

  record Key(String device, String net, String via) {}

  /** Час: корзины, люди, когда последний замер. Поля открыты – так их пишет JSON. */
  public static final class Slot {
    public String device;
    public String net;
    public String via;
    public long hour;
    public long lastAt;
    public int[] api = new int[API.length + 1];
    public int[] load = new int[LOAD.length + 1];
    public Set<Long> users = new HashSet<>();
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Clock clock;
  private final Path file;
  private final Map<Key, Slot[]> slots = new HashMap<>();
  private final Map<Long, Long> lastReport = new HashMap<>();

  @Autowired
  public ClientTimings(GroupbaseProperties props, Clock clock) {
    this(clock, props.dataDir().resolve("timings.json"));
  }

  ClientTimings(Clock clock, Path file) {
    this.clock = clock;
    this.file = file;
    load();
  }

  /** Принять замер; чужие значения (не из списка) – «unknown», лишнее и частое – отбрасывается. */
  public synchronized boolean report(long userId, Report r) {
    long now = clock.millis();
    Long last = lastReport.get(userId);
    if (last != null && now - last < MIN_GAP) {
      return false;
    }
    lastReport.put(userId, now);
    Key key =
        new Key(
            pick(r.device(), DEVICES, "computer"),
            pick(r.net(), NETS, "unknown"),
            pick(r.via(), VIA, "tunnel"));
    Slot s = slot(key, now);
    int n = 0;
    for (Integer ms : r.api() == null ? List.<Integer>of() : r.api()) {
      if (ms != null && ms >= 0 && ms <= 60_000 && n++ < MAX_SAMPLES) {
        s.api[bucket(ms, API)]++;
      }
    }
    if (r.load() != null && r.load() > 0 && r.load() <= 120_000) {
      s.load[bucket(r.load(), LOAD)]++;
    }
    s.users.add(userId);
    s.lastAt = now;
    return true;
  }

  /** Таблица за сутки: самые частые сочетания – сверху. */
  public synchronized List<Row> view() {
    long oldest = hour(clock.millis()) - (HOURS - 1) * HOUR;
    List<Row> out = new ArrayList<>();
    slots.forEach(
        (k, ring) -> {
          int[] api = new int[API.length + 1];
          int[] load = new int[LOAD.length + 1];
          Set<Long> people = new HashSet<>();
          long lastAt = 0;
          for (Slot s : ring) {
            if (s == null || s.hour < oldest) {
              continue;
            }
            add(api, s.api);
            add(load, s.load);
            people.addAll(s.users);
            lastAt = Math.max(lastAt, s.lastAt);
          }
          int samples = sum(api);
          if (samples + sum(load) == 0) {
            return;
          }
          out.add(
              new Row(
                  k.device(),
                  k.net(),
                  k.via(),
                  people.size(),
                  samples,
                  percentile(api, API, 0.5),
                  percentile(api, API, 0.95),
                  percentile(load, LOAD, 0.5),
                  lastAt));
        });
    out.sort(Comparator.comparingInt(Row::samples).reversed());
    return out;
  }

  private Slot slot(Key key, long now) {
    Slot[] ring = slots.computeIfAbsent(key, k -> new Slot[HOURS]);
    long h = hour(now);
    int i = (int) ((h / HOUR) % HOURS);
    Slot s = ring[i];
    if (s == null || s.hour != h) {
      s = new Slot();
      s.device = key.device();
      s.net = key.net();
      s.via = key.via();
      s.hour = h;
      ring[i] = s;
    }
    return s;
  }

  private static long hour(long millis) {
    return millis / HOUR * HOUR;
  }

  private static String pick(String v, Set<String> allowed, String fallback) {
    return v != null && allowed.contains(v) ? v : fallback;
  }

  static int bucket(int ms, int[] bounds) {
    for (int i = 0; i < bounds.length; i++) {
      if (ms <= bounds[i]) {
        return i;
      }
    }
    return bounds.length;
  }

  /** Верхняя граница корзины, где лежит перцентиль, – оценка «не дольше»; null – замеров нет. */
  static Integer percentile(int[] hist, int[] bounds, double q) {
    int total = sum(hist);
    if (total == 0) {
      return null;
    }
    long need = (long) Math.ceil(total * q);
    long seen = 0;
    for (int i = 0; i < hist.length; i++) {
      seen += hist[i];
      if (seen >= need) {
        return i < bounds.length ? bounds[i] : bounds[bounds.length - 1] * 2;
      }
    }
    return bounds[bounds.length - 1] * 2;
  }

  private static void add(int[] into, int[] from) {
    if (from == null) {
      return;
    }
    for (int i = 0; i < Math.min(into.length, from.length); i++) {
      into[i] += from[i];
    }
  }

  private static int sum(int[] a) {
    int s = 0;
    for (int v : a) {
      s += v;
    }
    return s;
  }

  @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT5M")
  @PreDestroy
  synchronized void save() {
    List<Slot> list = new ArrayList<>();
    slots
        .values()
        .forEach(
            ring -> list.addAll(java.util.Arrays.stream(ring).filter(s -> s != null).toList()));
    if (list.isEmpty() && !Files.exists(file)) {
      return;
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

  private void load() {
    if (!Files.exists(file)) {
      return;
    }
    try {
      long oldest = hour(clock.millis()) - (HOURS - 1) * HOUR;
      for (Slot s : JSON.readValue(file.toFile(), Slot[].class)) {
        if (s.hour < oldest
            || s.api == null
            || s.api.length != API.length + 1
            || s.load == null
            || s.load.length != LOAD.length + 1) {
          continue;
        }
        Key key =
            new Key(
                pick(s.device, DEVICES, "computer"),
                pick(s.net, NETS, "unknown"),
                pick(s.via, VIA, "tunnel"));
        slots.computeIfAbsent(key, k -> new Slot[HOURS])[(int) ((s.hour / HOUR) % HOURS)] = s;
      }
    } catch (RuntimeException ex) {
      // Испорченный файл – начинаем с чистого листа.
    }
  }
}
