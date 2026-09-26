package app.groupbase.hosts;

import app.groupbase.access.AccessService;
import app.groupbase.accounts.InstanceSettings;
import app.groupbase.accounts.PublicUrl;
import app.groupbase.auth.Tokens;
import app.groupbase.backup.CloudFolders;
import app.groupbase.cli.Main;
import app.groupbase.config.GroupbaseProperties;
import app.groupbase.desktop.DesktopBridge;
import app.groupbase.hosts.HostPlan.Kind;
import app.groupbase.hosts.HostPlan.Plan;
import app.groupbase.hosts.SiteFolder.Host;
import app.groupbase.hosts.SiteFolder.Request;
import app.groupbase.hosts.SiteFolder.Snap;
import app.groupbase.store.SettingsStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Сайт на нескольких компьютерах хоста: дома — на ПК, в вузе — на ноутбуке. Работает сайт всегда на
 * одном из них, а передают его друг другу через общую папку облачного диска (Яндекс Диск, OneDrive,
 * iCloud…), которая есть на обоих:
 *
 * <ul>
 *   <li>хост после изменений сохраняет туда снимок и раз в минуту отмечается, что он на связи;
 *   <li>закрыли приложение на одном компьютере — другой при запуске (или сразу, если он открыт)
 *       берёт свежие данные и продолжает сам;
 *   <li>сайт работает на другом компьютере — здесь страница ожидания и кнопка «Перенести сюда»:
 *       хост сохраняет последний снимок, останавливается, а этот берёт данные;
 *   <li>другой компьютер давно не на связи (выключили, не закрыв) — сайт можно запустить здесь с
 *       его последним снимком; если сайт по-прежнему нигде не отвечает — это случится само.
 * </ul>
 *
 * Не хост («ожидание») — туннель выключен, API сайта отвечает «standby». Всё это — только в
 * приложении хоста и только после включения в «Настройки → Сервер».
 */
@Service
public class HostService implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(HostService.class);

  /** Роль этого компьютера. */
  public enum Role {
    /** Перенос между компьютерами выключен — обычная работа. */
    OFF,
    HOST,
    /** Компьютер проснулся: пока не ясно, не перенесли ли сайт, — туннель выключен. */
    CHECKING,
    STANDBY,
    /** Ждём ответа хоста или пока облако докачает данные. */
    WAITING,
    /** Данные взяты — сервер перезапускается. */
    SWITCHING,
    /** Сайт перенесён отсюда на другой компьютер по коду — здесь не открывается. */
    MOVED;

    public String id() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  static final long TICK_MS = 5_000;
  static final long HEARTBEAT_MS = 60_000;
  static final long SNAPSHOT_GAP_MS = 60_000;
  static final long WAKE_GAP_MS = 2 * 60_000;
  static final long CHECK_MS = 40_000;
  static final long REQUEST_FRESH_MS = 90_000;
  static final long ANSWER_MS = 3 * 60_000;
  static final long AUTO_TAKE_MS = 10 * 60_000;
  static final long PROBE_MS = 60_000;
  static final long MIRROR_QUIET_MS = Duration.ofDays(2).toMillis();
  static final int KEEP = 3;
  static final String SITE_ID = "hosts.site_id";

  public record Computer(String id, String name) {}

  /** Хост, если это не этот компьютер. */
  public record Other(String name, String state, long heartbeat, boolean fresh) {}

  public record Choice(String label, String path) {}

  public record View(
      boolean available,
      boolean enabled,
      String role,
      String plan,
      String message,
      String error,
      Computer computer,
      Other other,
      Long snapshotAt,
      String snapshotBy,
      String action,
      String folder,
      String cloud,
      List<Choice> choices,
      Integer have,
      Integer total) {}

  /** Сайт, найденный в облачной папке (первый запуск на новом компьютере). */
  public record Found(String path, String cloud, String name, String host, Long at) {}

  /** Ошибка, понятная пользователю. */
  public static final class Problem extends RuntimeException {
    Problem(String message) {
      super(message);
    }
  }

  enum Seen {
    HERE,
    OTHER,
    NOBODY,
    UNKNOWN
  }

  /**
   * Кто отвечает по адресу сайта: номер компьютера и его поколение (-1 — сервер без переноса между
   * компьютерами, поколение неизвестно).
   */
  record Probe(Seen seen, String computer, long epoch) {}

  private final GroupbaseProperties props;
  private final JdbcClient db;
  private final SettingsStore settings;
  private final InstanceSettings instance;
  private final AccessService access;
  private final DesktopBridge bridge;
  private final PublicUrl publicUrl;
  private final Clock clock;
  private final Path testRoot;
  private final long tickMs;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(6))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  /** Настройки этого компьютера; null — не приложение хоста. */
  private final HostsConfig cfg;

  private SiteFolder folder;
  private volatile Role role = Role.OFF;
  private volatile Plan plan;
  private volatile String message;
  private volatile String problem;
  private Integer have;
  private Integer total;

  private long epoch;
  private long startedAt;
  private long lastTick;
  private long lastHeartbeat;
  private long lastSnapshotAt;
  private long lastProbe;
  private long checkUntil;
  private long requestedAt;
  private String fingerprint = "";
  private Snap lastSnapshot;

  /** Сайт перешёл с другого компьютера: номера журнала изменений — вперёд (см. hostStep). */
  private boolean skipSeq;

  private volatile Probe lastSeen;

  private ScheduledExecutorService timer;
  private volatile boolean running;

  /** До этого времени изменения на сайте не принимаются: его забирает другой компьютер по коду. */
  private volatile long movingUntil;

  public HostService(
      GroupbaseProperties props,
      JdbcClient db,
      SettingsStore settings,
      InstanceSettings instance,
      AccessService access,
      DesktopBridge bridge,
      PublicUrl publicUrl,
      Clock clock,
      Environment env) {
    this.props = props;
    this.db = db;
    this.settings = settings;
    this.instance = instance;
    this.access = access;
    this.bridge = bridge;
    this.publicUrl = publicUrl;
    this.clock = clock;
    // Для тестов: папка «облачного диска» вместо настоящих.
    String root = env.getProperty("groupbase.hosts.cloud-root", "");
    this.testRoot = root.isBlank() ? null : Path.of(root).toAbsolutePath().normalize();
    // Для тестов: шаги — по команде теста, а не по таймеру.
    this.tickMs = env.getProperty("groupbase.hosts.tick-ms", Long.class, TICK_MS);
    HostsConfig c = null;
    if (props.desktop().enabled()) {
      try {
        c = HostsConfig.load(props.dataDir());
      } catch (IOException e) {
        log.warn("Не прочитать настройки компьютеров хоста: {}", e.getMessage());
      }
    }
    this.cfg = c;
    if (cfg != null && cfg.moved() > 0) {
      // Сайт перенесли отсюда по коду: старые данные здесь не должны заработать снова.
      role = Role.MOVED;
      message = MOVED_MESSAGE;
      access.suspend(message);
    } else if (cfg != null && cfg.dir() != null) {
      folder = new SiteFolder(cfg.dir());
      // До того как AccessService поднимет туннель: не хост — туннель не нужен.
      synchronized (this) {
        decide(clock.millis(), false);
      }
    }
  }

  static final String MOVED_MESSAGE = "Сайт перенесён на другой компьютер.";

  /** Сайт работает здесь (или перенос выключен): API открыт, фоновые копии делаются. */
  public boolean serving() {
    Role r = role;
    return r == Role.OFF || r == Role.HOST;
  }

  /** Сайт забирает другой компьютер по коду: читать можно, менять — нет. */
  public boolean moving() {
    return clock.millis() < movingUntil;
  }

  /** Запретить изменения до этого времени (0 — снять запрет). */
  public void lockWrites(long until) {
    movingUntil = until;
  }

  /** Код переноса даёт только компьютер, на котором сайт сейчас работает. */
  public void requireServing() {
    requireApp();
    if (!serving()) {
      throw new Problem("Код переноса даёт компьютер, на котором сейчас работает сайт");
    }
  }

  /**
   * Сайт забрали по коду: здесь он больше не открывается и туннель не поднимается — ни сейчас, ни
   * после перезапуска. Перенос через облачную папку при этом выключается (другие компьютеры её не
   * заберут сами), чтобы сайт не заработал в двух местах.
   */
  public synchronized void movedAway() {
    long now = clock.millis();
    if (folder != null) {
      try {
        folder.writeHost(record(SiteFolder.DETACHED, now, lastSnapshot, null));
      } catch (IOException e) {
        // Папки не видно — другие компьютеры и так её не читают.
      }
      folder = null;
      cfg.setDir(null);
    }
    cfg.setMoved(now);
    saveCfg();
    movingUntil = 0;
    role = Role.MOVED;
    plan = null;
    message = MOVED_MESSAGE;
    problem = null;
    access.suspend(message);
    log.info("Сайт перенесён на другой компьютер по коду — этот больше не хост");
  }

  /**
   * «Вернуть сайт сюда» после переноса по коду — если на новом компьютере он так и не заработал.
   * Сначала проверяем, что по адресу сайта никто не отвечает: иначе будут два разных сайта.
   */
  public View returnHere() {
    requireApp();
    synchronized (this) {
      if (role != Role.MOVED) {
        return view();
      }
    }
    Probe seen = probe();
    lastSeen = seen;
    if (seen.seen() == Seen.OTHER || seen.seen() == Seen.HERE) {
      throw new Problem(
          "Сайт уже работает на другом компьютере. Чтобы вернуть его сюда, возьмите там код"
              + " переноса и введите его здесь.");
    }
    synchronized (this) {
      cfg.setMoved(0);
      saveCfg();
      role = Role.OFF;
      message = null;
      problem = null;
      access.resume();
      log.info("Сайт возвращён на этот компьютер");
      return view();
    }
  }

  /**
   * Перед тем как взять сайт по коду: этот компьютер начинает с чистого листа — без общей папки,
   * заказанных снимков и отметки «перенесён».
   */
  public synchronized void prepareForPull() {
    if (cfg == null) {
      return;
    }
    cfg.setDir(null);
    cfg.setPending(null, false);
    cfg.setClaim(false);
    cfg.setMoved(0);
    cfg.setFailed(null, "");
    cfg.setError("");
    saveCfg();
    folder = null;
    role = Role.SWITCHING;
    message = "Переносим сайт на этот компьютер…";
    access.suspend(message);
  }

  /** Текст для ответа API в режиме ожидания. */
  public String standbyMessage() {
    String m = message;
    return m == null || m.isBlank() ? "Сайт сейчас работает на другом компьютере" : m;
  }

  // ---------- решение ----------

  private Plan plan(long now) throws IOException {
    return HostPlan.decide(
        cfg.computerId(),
        cfg.epoch(),
        cfg.snapshot(),
        cfg.claim(),
        folder.host().orElse(null),
        folder.snapshotNames(),
        now);
  }

  /** Что делать сейчас; resume — поднять туннель, если здесь снова хост. */
  private void decide(long now, boolean resume) {
    if (!Files.isDirectory(folder.dir())) {
      toStandby(
          null,
          "Не видно облачной папки сайта. Запустите облачный диск — сайт продолжит работу сам.");
      return;
    }
    if (!cfg.pending().isBlank()) {
      waitFor(cfg.pending());
      return;
    }
    Plan p;
    try {
      p = plan(now);
    } catch (IOException e) {
      toStandby(null, "Облако ещё докачивает сведения о хосте — подождите минуту.");
      return;
    }
    act(p, now, resume);
  }

  private void act(Plan p, long now, boolean resume) {
    plan = p;
    String other = p.other() == null ? "другом компьютере" : "«" + p.other().computer() + "»";
    switch (p.kind()) {
      case HOST -> {
        Probe seen = lastSeen;
        if (role != Role.HOST
            && seen != null
            && seen.seen() == Seen.OTHER
            && seen.epoch() > cfg.epoch()) {
          // По папке сайт наш, но отвечает он с другого компьютера — там облако не обновляется.
          toStandby(
              p,
              "Сайт сейчас работает на другом компьютере, но облачная папка на нём не обновляется."
                  + " Проверьте облачный диск там.");
          if (now - lastProbe >= PROBE_MS) {
            lastProbe = now;
            probeLater();
          }
          return;
        }
        becomeHost(now, resume, p.other(), false);
      }
      case TAKE -> {
        if (p.snapshot().equals(cfg.failed(Main.version()))) {
          toStandby(p, "Сайт закрыт на компьютере " + other + ", но его данные не взялись.");
          problem = cfg.error();
          return;
        }
        takeWhenReady(p.snapshot(), false);
      }
      case LIVE -> {
        if (role == Role.WAITING && requestedAt > 0) {
          if (now - requestedAt > ANSWER_MS) {
            requestedAt = 0;
            folder.deleteRequest();
            toStandby(
                p,
                "Компьютер "
                    + other
                    + " не ответил. Если он выключен или без интернета, подождите пару минут —"
                    + " появится кнопка «Запустить здесь».");
          } else {
            refreshRequest(now);
          }
        } else {
          toStandby(p, "Сайт сейчас работает на " + where(p.other()) + ".");
        }
      }
      case SILENT -> {
        toStandby(
            p,
            (p.other() == null
                    ? "Сайт недавно запускали на другом компьютере"
                    : "Компьютер " + other)
                + (p.other() == null
                    ? "."
                    : " не на связи с " + time(p.other().heartbeat()) + "."));
        maybeAutoTake(p, now);
      }
      case HANDED -> {
        String to = p.other() == null ? null : p.other().toName();
        toStandby(
            p,
            (to == null || to.isBlank()
                    ? "Сайт передан на другой компьютер"
                    : "Сайт передан на компьютер «" + to + "»")
                + (p.quietFor() > ANSWER_MS
                    ? ", но он его пока не принял."
                    : " — он вот-вот продолжит работу."));
      }
      case DETACHED ->
          toStandby(p, "На компьютере " + other + " выключили перенос сайта между компьютерами.");
    }
  }

  private static String where(Host h) {
    return h == null ? "другом компьютере" : "компьютере «" + h.computer() + "»";
  }

  private String time(long at) {
    return java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.forLanguageTag("ru"))
        .format(java.time.Instant.ofEpochMilli(at).atZone(props.timezone()));
  }

  private void toStandby(Plan p, String why) {
    if (role != Role.WAITING || requestedAt == 0) {
      role = Role.STANDBY;
    }
    plan = p;
    message = why;
    have = null;
    total = null;
    access.suspend(why);
  }

  /** Взять снимок, когда облако его докачает; потом перезапуск (данные берутся до сервера). */
  private void takeWhenReady(String snapshot, boolean claim) {
    try {
      SiteSnapshot.ready(folder, snapshot);
      switchTo(snapshot, claim);
    } catch (SiteSnapshot.Incomplete e) {
      role = Role.WAITING;
      message = e.getMessage() + " — сайт продолжит работу здесь сам.";
      have = e.have;
      total = e.total;
      access.suspend(message);
    } catch (IOException | RuntimeException e) {
      role = Role.STANDBY;
      problem = "Не удалось взять данные: " + e.getMessage();
      access.suspend(problem);
    }
  }

  private void waitFor(String snapshot) {
    try {
      SiteSnapshot.ready(folder, snapshot);
      // Готово, но при запуске взять не вышло (или заказали только что) — перезапуск возьмёт.
      restartSoon();
    } catch (SiteSnapshot.Incomplete e) {
      role = Role.WAITING;
      message = e.getMessage() + " — сайт продолжит работу здесь сам.";
      have = e.have;
      total = e.total;
      access.suspend(message);
    } catch (IOException | RuntimeException e) {
      cfg.setPending(null, false);
      saveCfg();
      role = Role.STANDBY;
      problem = "Не удалось взять данные: " + e.getMessage();
      access.suspend(problem);
    }
  }

  private void switchTo(String snapshot, boolean claim) {
    cfg.setPending(snapshot, claim);
    saveCfg();
    restartSoon();
  }

  private void restartSoon() {
    role = Role.SWITCHING;
    message = "Переносим сайт на этот компьютер…";
    access.suspend(message);
    if (bridge.enabled()) {
      // Сначала уйдёт ответ страницы, потом оболочка перезапустит сервер.
      CompletableFuture.delayedExecutor(700, TimeUnit.MILLISECONDS).execute(bridge::requestRestart);
    }
  }

  private void becomeHost(long now, boolean resume, Host previous, boolean bump) {
    long e = Math.max(cfg.epoch(), previous == null ? 0 : previous.epoch());
    boolean moved = previous != null && !cfg.computerId().equals(previous.computerId());
    if (bump || previous == null || moved) {
      e++;
    }
    skipSeq = bump || moved;
    epoch = e;
    role = Role.HOST;
    plan = null;
    message = null;
    problem = null;
    have = null;
    total = null;
    requestedAt = 0;
    startedAt = now;
    cfg.setEpoch(e);
    cfg.setClaim(false);
    cfg.setPending(null, false);
    cfg.setError("");
    saveCfg();
    String snap = cfg.snapshot();
    lastSnapshot =
        snap.isBlank() ? null : new Snap(snap, SiteFolder.timeOf(snap), 0, cfg.computerName());
    // Первый же шаг сохранит снимок уже в новом поколении — по нему другие поймут, чей сайт.
    fingerprint = "";
    lastSnapshotAt = 0;
    writeHeartbeat(now);
    folder
        .request()
        .filter(r -> cfg.computerId().equals(r.computerId()))
        .ifPresent(r -> folder.deleteRequest());
    if (resume) {
      access.resume();
    }
  }

  // ---------- шаги ----------

  private void tick() {
    try {
      synchronized (this) {
        step(clock.millis());
      }
    } catch (RuntimeException e) {
      log.warn("Компьютеры хоста: {}", e.getMessage());
    }
  }

  /** Один шаг (раз в пять секунд). Для тестов — с заданным временем. */
  synchronized void step(long now) {
    long gap = lastTick == 0 ? 0 : now - lastTick;
    lastTick = now;
    if (folder == null) {
      return;
    }
    switch (role) {
      case HOST -> hostStep(now, gap);
      case CHECKING -> checkingStep(now);
      case STANDBY, WAITING -> decide(now, true);
      default -> {
        // OFF, SWITCHING и MOVED — делать нечего.
      }
    }
  }

  private void hostStep(long now, long gap) {
    if (gap > WAKE_GAP_MS) {
      // Компьютер спал: пока его не было, сайт могли перенести. Туннель — выключить до проверки.
      role = Role.CHECKING;
      checkUntil = now + CHECK_MS;
      message = "Проверяем, не перенесли ли сайт, пока компьютер спал…";
      access.suspend(message);
      probeLater();
      return;
    }
    if (!Files.isDirectory(folder.dir())) {
      problem = "Не видно облачной папки — снимки для других компьютеров не сохраняются.";
      return;
    }
    Optional<Request> r = folder.request();
    if (r.isPresent()
        && !cfg.computerId().equals(r.get().computerId())
        && now - r.get().at() < REQUEST_FRESH_MS) {
      handOff(r.get(), now);
      return;
    }
    if (displaced(now)) {
      return;
    }
    if (skipSeq) {
      // Если прежний хост не успел сохранить последние изменения, телефоны уже видели их номера.
      // Новые изменения — с номерами дальше: иначе устройства посчитают их уже полученными.
      try {
        if (db.sql("UPDATE sqlite_sequence SET seq = seq + 1000 WHERE name = 'changes'").update()
            == 0) {
          db.sql("INSERT INTO sqlite_sequence (name, seq) VALUES ('changes', 1000)").update();
        }
      } catch (RuntimeException e) {
        log.warn("Не сдвинуть номера журнала изменений: {}", e.getMessage());
      }
      skipSeq = false;
    }
    if (now - lastSnapshotAt >= SNAPSHOT_GAP_MS) {
      String fp = fingerprint();
      if (!fp.equals(fingerprint)) {
        snapshot(now, fp);
      }
    }
    if (now - lastHeartbeat >= HEARTBEAT_MS) {
      writeHeartbeat(now);
    }
    if (now - lastProbe >= PROBE_MS) {
      lastProbe = now;
      probeLater();
    }
  }

  private void checkingStep(long now) {
    if (displaced(now)) {
      return;
    }
    Probe p = lastSeen;
    if (p != null && p.seen() == Seen.OTHER && p.epoch() > epoch) {
      standDown(now);
      return;
    }
    if (now < checkUntil) {
      return;
    }
    role = Role.HOST;
    message = null;
    lastHeartbeat = 0;
    writeHeartbeat(now);
    access.resume();
  }

  /** Сайт уже работает на другом компьютере (его поколение новее) — уступаем. */
  private boolean displaced(long now) {
    Host h;
    try {
      h = folder.host().orElse(null);
    } catch (IOException e) {
      return false;
    }
    List<String> names = folder.snapshotNames();
    long newest = names.isEmpty() ? -1 : SiteFolder.epochOf(names.getLast());
    Probe p = lastSeen;
    if (newest > epoch || p != null && p.seen() == Seen.OTHER && p.epoch() > epoch) {
      standDown(now);
      return true;
    }
    if (h != null && !cfg.computerId().equals(h.computerId())) {
      if (h.epoch() > epoch
          || h.epoch() == epoch && h.computerId().compareTo(cfg.computerId()) < 0) {
        standDown(now);
        return true;
      }
      // Старая запись другого компьютера (он ещё не знает о нас) — вернём свою.
      writeHeartbeat(now);
    }
    return false;
  }

  private void standDown(long now) {
    log.info("Сайт перенесли на другой компьютер — этот переходит в ожидание");
    role = Role.STANDBY;
    Plan p;
    try {
      p = plan(now);
    } catch (IOException e) {
      p = null;
    }
    Host h = p == null ? null : p.other();
    toStandby(
        p,
        h != null && !cfg.computerId().equals(h.computerId())
            ? "Сайт перенесли на компьютер «" + h.computer() + "»."
            : "Сайт перенесли на другой компьютер.");
  }

  private void handOff(Request r, long now) {
    log.info("Другой компьютер просит передать ему сайт");
    role = Role.SWITCHING;
    message = "Передаём сайт на компьютер «" + r.computer() + "»…";
    try {
      Snap s = snapshot(now, fingerprint());
      folder.writeHost(record(SiteFolder.STOPPED, now, s, r));
    } catch (RuntimeException e) {
      role = Role.HOST;
      problem = "Не удалось передать сайт: " + e.getMessage();
      return;
    } catch (IOException e) {
      role = Role.HOST;
      problem = "Не удалось передать сайт: " + e.getMessage();
      return;
    }
    toStandby(null, "Сайт передан на компьютер «" + r.computer() + "».");
    try {
      plan = plan(now);
    } catch (IOException e) {
      // план обновится на следующем шаге
    }
  }

  private void refreshRequest(long now) {
    try {
      folder.writeRequest(new Request(cfg.computerId(), cfg.computerName(), now));
    } catch (IOException e) {
      problem = "Не удалось записать в облачную папку: " + e.getMessage();
    }
  }

  private void maybeAutoTake(Plan p, long now) {
    if (p.quietFor() < AUTO_TAKE_MS || now - lastProbe < PROBE_MS) {
      return;
    }
    lastProbe = now;
    Thread.ofVirtual()
        .name("hosts-probe")
        .start(
            () -> {
              Probe seen = probe();
              lastSeen = seen;
              if (seen.seen() != Seen.NOBODY) {
                return;
              }
              synchronized (this) {
                if (role != Role.STANDBY) {
                  return;
                }
                try {
                  Plan again = plan(clock.millis());
                  if (again.kind() == Kind.SILENT && again.quietFor() >= AUTO_TAKE_MS) {
                    log.info("Хост давно не на связи, сайт нигде не отвечает — запускаем здесь");
                    startHere(again, clock.millis());
                  }
                } catch (IOException | RuntimeException e) {
                  // попробуем на следующем шаге
                }
              }
            });
  }

  /** Запустить сайт здесь с самым новым готовым снимком (или без него, если данные уже свежие). */
  private void startHere(Plan p, long now) throws IOException {
    List<String> names = folder.snapshotNames();
    String newest = names.isEmpty() ? null : names.getLast();
    if (newest == null
        || newest.equals(cfg.snapshot())
        || SiteFolder.epochOf(newest) < cfg.epoch()) {
      becomeHost(now, true, p.other(), true);
      return;
    }
    SiteSnapshot.ready(folder, newest);
    switchTo(newest, true);
  }

  private void probeLater() {
    Thread.ofVirtual().name("hosts-probe").start(() -> lastSeen = probe());
  }

  /**
   * Кто сейчас отвечает по адресу сайта. Ответ без метки сервера — это страница туннеля: сайт нигде
   * не работает. Нет связи — неизвестно.
   */
  Probe probe() {
    String base = publicUrl.get().orElse("");
    if (!base.startsWith("https://")) {
      return new Probe(Seen.UNKNOWN, null, 0);
    }
    try {
      HttpResponse<String> r =
          http.send(
              HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + "/api/host/whoami"))
                  .timeout(Duration.ofSeconds(8))
                  .header("Accept", "application/json")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (r.headers().firstValue("X-Groupbase").isEmpty()) {
        return new Probe(Seen.NOBODY, null, 0);
      }
      if (r.statusCode() != 200) {
        // Отвечает сервер groupbase, но не хост в переносе (старая версия, ожидание) — сайт жив.
        return new Probe(Seen.OTHER, null, -1);
      }
      JsonNode j = SiteFolder.JSON.readTree(r.body());
      String who = j.path("computer").asString("");
      if (cfg.computerId().equals(who)) {
        return new Probe(Seen.HERE, who, j.path("epoch").asLong(0));
      }
      return new Probe(Seen.OTHER, who, j.path("epoch").asLong(0));
    } catch (IOException | RuntimeException e) {
      return new Probe(Seen.UNKNOWN, null, 0);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Probe(Seen.UNKNOWN, null, 0);
    }
  }

  /**
   * Для /api/host/whoami: какой компьютер отвечает по адресу сайта и в каком поколении. Только
   * случайный номер — имя компьютера (в нём бывает имя человека) наружу не отдаётся.
   */
  public Optional<java.util.Map<String, Object>> whoami() {
    if (cfg == null || folder == null || role != Role.HOST) {
      return Optional.empty();
    }
    return Optional.of(
        java.util.Map.of(
            "site",
            settings.get(SITE_ID).orElse(""),
            "computer",
            cfg.computerId(),
            "epoch",
            epoch));
  }

  /** Имя компьютера по номеру — из записи о хосте в общей папке. */
  private String nameOf(String computerId) {
    if (computerId == null || folder == null) {
      return null;
    }
    try {
      return folder
          .host()
          .filter(h -> computerId.equals(h.computerId()))
          .map(Host::computer)
          .orElse(null);
    } catch (IOException e) {
      return null;
    }
  }

  // ---------- снимки и запись о хосте ----------

  private Snap snapshot(long now, String fp) {
    try {
      Snap s =
          SiteSnapshot.write(
              db,
              props.dataDir(),
              folder,
              new SiteSnapshot.Meta(
                  epoch, cfg.computerId(), cfg.computerName(), Main.version(), props.http().port()),
              now);
      lastSnapshot = s;
      lastSnapshotAt = now;
      fingerprint = fp;
      cfg.setData(epoch, s.name());
      saveCfg();
      folder.rotate(KEEP, now, MIRROR_QUIET_MS);
      problem = null;
      writeHeartbeat(now);
      return s;
    } catch (IOException e) {
      lastSnapshotAt = now;
      problem = "Не удалось сохранить снимок в облачную папку: " + e.getMessage();
      log.warn("Снимок для других компьютеров не сохранён: {}", e.getMessage());
      return lastSnapshot;
    }
  }

  /** Изменилась ли база: размер и время файла базы и её журнала. */
  private String fingerprint() {
    StringBuilder sb = new StringBuilder();
    for (Path p : List.of(props.databaseFile(), props.dataDir().resolve("groupbase.db-wal"))) {
      try {
        sb.append(Files.size(p)).append(':').append(Files.getLastModifiedTime(p).toMillis());
      } catch (IOException e) {
        sb.append('-');
      }
      sb.append(';');
    }
    return sb.toString();
  }

  private Host record(String state, long now, Snap snap, Request to) {
    return new Host(
        SiteFolder.FORMAT,
        cfg.computerId(),
        cfg.computerName(),
        epoch,
        state,
        now,
        startedAt,
        snap,
        to == null ? null : to.computerId(),
        to == null ? null : to.computer(),
        Main.version());
  }

  private void writeHeartbeat(long now) {
    try {
      folder.writeHost(record(SiteFolder.RUNNING, now, lastSnapshot, null));
      lastHeartbeat = now;
    } catch (IOException e) {
      problem = "Не удаётся записать в облачную папку: " + e.getMessage();
    }
  }

  private void saveCfg() {
    try {
      cfg.save();
    } catch (IOException e) {
      log.warn("Не сохранить настройки компьютера хоста: {}", e.getMessage());
    }
  }

  // ---------- действия из интерфейса ----------

  public synchronized View view() {
    boolean available = cfg != null;
    Host h = null;
    Long snapAt = null;
    String snapBy = null;
    if (folder != null) {
      try {
        h = folder.host().orElse(null);
      } catch (IOException e) {
        h = null;
      }
      if (h != null && h.snapshot() != null) {
        snapAt = h.snapshot().createdAt();
        snapBy = h.snapshot().computer();
      }
      if (lastSnapshot != null && (snapAt == null || lastSnapshot.createdAt() > snapAt)) {
        snapAt = lastSnapshot.createdAt();
        snapBy = lastSnapshot.computer();
      }
    }
    Other other = null;
    if (h != null && cfg != null && !cfg.computerId().equals(h.computerId())) {
      long quiet = clock.millis() - h.heartbeat();
      other =
          new Other(
              h.computer(),
              h.state(),
              h.heartbeat(),
              SiteFolder.RUNNING.equals(h.state()) && quiet < HostPlan.FRESH_MS);
    }
    Plan p = plan;
    String action = null;
    if (role == Role.STANDBY && p != null) {
      action =
          switch (p.kind()) {
            case LIVE -> "request";
            case SILENT, DETACHED -> "start";
            case HANDED -> p.quietFor() > ANSWER_MS ? "back" : null;
            default -> null;
          };
    }
    if (role == Role.STANDBY && p == null && folder != null && !Files.isDirectory(folder.dir())) {
      action = null;
    }
    if (role == Role.MOVED) {
      action = "return";
    }
    List<Choice> choices = new ArrayList<>();
    if (available && folder == null) {
      for (CloudFolders.Folder f : roots()) {
        choices.add(new Choice(f.label(), f.path().toString()));
      }
    }
    String err = problem;
    if ((err == null || err.isBlank()) && cfg != null && !cfg.error().isBlank()) {
      err = cfg.error();
    }
    return new View(
        available,
        folder != null,
        role.id(),
        p == null ? null : p.kind().name().toLowerCase(Locale.ROOT),
        message,
        err,
        cfg == null ? null : new Computer(cfg.computerId(), cfg.computerName()),
        other,
        snapAt,
        snapBy,
        action,
        folder == null ? null : folder.dir().toString(),
        folder == null ? null : cloudLabel(folder.dir()),
        choices,
        have,
        total);
  }

  /** Включить: сайт этого компьютера — в общую папку выбранного облачного диска. */
  public View enable(String cloudRoot) {
    requireApp();
    Path root = chosenRoot(cloudRoot);
    synchronized (this) {
      if (folder != null) {
        throw new Problem("Уже включено");
      }
      String id =
          settings
              .get(SITE_ID)
              .filter(s -> !s.isBlank())
              .orElseGet(
                  () -> {
                    String s =
                        Tokens.newToken()
                            .toLowerCase(Locale.ROOT)
                            .replaceAll("[^a-z0-9]", "")
                            .substring(0, 10);
                    settings.set(SITE_ID, s);
                    return s;
                  });
      SiteFolder f = SiteFolder.in(root, id);
      long now = clock.millis();
      try {
        Files.createDirectories(f.dir());
        if (f.site().isEmpty()) {
          f.writeSite(new SiteFolder.Site(SiteFolder.FORMAT, id, instance.name(), now));
        }
      } catch (IOException e) {
        throw new Problem("Не удалось создать папку в облачном диске: " + e.getMessage());
      }
      folder = f;
      cfg.setDir(f.dir());
      cfg.computerId();
      cfg.computerName();
      saveCfg();
      lastTick = now;
      decide(now, true);
      if (role == Role.HOST) {
        snapshot(now, fingerprint());
      }
      return view();
    }
  }

  /**
   * Выключить (только на хосте): сайт остаётся здесь, другие компьютеры его больше не берут сами.
   */
  public synchronized View disable() {
    requireApp();
    if (folder == null) {
      return view();
    }
    if (role != Role.HOST) {
      throw new Problem("Выключить можно на компьютере, где сейчас работает сайт");
    }
    try {
      folder.writeHost(record(SiteFolder.DETACHED, clock.millis(), lastSnapshot, null));
    } catch (IOException e) {
      // Папки уже не видно — тем более выключаем.
    }
    folder = null;
    cfg.setDir(null);
    saveCfg();
    role = Role.OFF;
    plan = null;
    message = null;
    problem = null;
    return view();
  }

  public synchronized View rename(String name) {
    requireApp();
    String n = name == null ? "" : name.strip();
    if (n.isEmpty() || n.length() > 40) {
      throw new Problem("Имя компьютера — от 1 до 40 символов");
    }
    cfg.setComputerName(n);
    saveCfg();
    if (role == Role.HOST) {
      writeHeartbeat(clock.millis());
    }
    return view();
  }

  public synchronized View saveNow() {
    requireApp();
    if (role != Role.HOST) {
      throw new Problem("Снимок сохраняет компьютер, на котором сейчас работает сайт");
    }
    snapshot(clock.millis(), fingerprint());
    if (problem != null) {
      throw new Problem(problem);
    }
    return view();
  }

  /** «Перенести сюда» / «Запустить здесь» на странице ожидания. */
  public View takeOver() {
    requireApp();
    Plan p;
    synchronized (this) {
      if (folder == null || role == Role.HOST) {
        return view();
      }
      if (!Files.isDirectory(folder.dir())) {
        throw new Problem("Не видно облачной папки сайта — запустите облачный диск");
      }
      long now = clock.millis();
      try {
        p = plan(now);
      } catch (IOException e) {
        throw new Problem("Облако ещё докачивает сведения о хосте — подождите минуту");
      }
      switch (p.kind()) {
        case LIVE -> {
          refreshRequest(now);
          requestedAt = now;
          role = Role.WAITING;
          plan = p;
          message =
              "Попросили компьютер «" + p.other().computer() + "» передать сайт — ждём ответа…";
          return view();
        }
        case HOST -> {
          becomeHost(now, true, p.other(), false);
          return view();
        }
        case TAKE -> {
          takeWhenReady(p.snapshot(), false);
          return view();
        }
        default -> {
          // SILENT, HANDED, DETACHED — сначала убедимся, что сайт больше нигде не отвечает.
        }
      }
    }
    Probe seen = probe();
    lastSeen = seen;
    if (seen.seen() == Seen.OTHER) {
      String name;
      synchronized (this) {
        name = nameOf(seen.computer());
      }
      throw new Problem(
          "Сайт сейчас отвечает с "
              + (name == null ? "другого компьютера" : "компьютера «" + name + "»")
              + ": он включён, а облачная папка на нём не обновляется. Закройте groupbase там или"
              + " проверьте облачный диск.");
    }
    synchronized (this) {
      if (role == Role.HOST || role == Role.SWITCHING) {
        return view();
      }
      try {
        startHere(p, clock.millis());
      } catch (SiteSnapshot.Incomplete e) {
        throw new Problem(e.getMessage() + " — подождите немного и нажмите ещё раз");
      } catch (IOException e) {
        throw new Problem("Не удалось взять данные: " + e.getMessage());
      }
      return view();
    }
  }

  // ---------- первый запуск на новом компьютере ----------

  /** Сайты в облачных папках этого компьютера. */
  public List<Found> sites() {
    List<Found> out = new ArrayList<>();
    for (CloudFolders.Folder root : roots()) {
      Path base = root.path().resolve(SiteFolder.ROOT);
      if (!Files.isDirectory(base)) {
        continue;
      }
      try (var ds = Files.newDirectoryStream(base, Files::isDirectory)) {
        for (Path d : ds) {
          SiteFolder f = new SiteFolder(d);
          try {
            Optional<SiteFolder.Site> s = f.site();
            if (s.isEmpty()) {
              continue;
            }
            Host h = null;
            try {
              h = f.host().orElse(null);
            } catch (IOException e) {
              h = null;
            }
            List<String> names = f.snapshotNames();
            Long at =
                h != null
                    ? Long.valueOf(h.heartbeat())
                    : names.isEmpty() ? null : SiteFolder.timeOf(names.getLast());
            out.add(
                new Found(
                    f.dir().toString(),
                    root.label(),
                    s.get().name(),
                    h == null ? null : h.computer(),
                    at));
          } catch (IOException e) {
            // Папка ещё докачивается — покажем в следующий раз.
          }
        }
      } catch (IOException e) {
        // Нет доступа — пропускаем.
      }
    }
    return out;
  }

  /**
   * Подключить новый компьютер к сайту из облачной папки: взять его данные (после перезапуска), а
   * работать здесь сайт начнёт, если на другом компьютере он закрыт, — иначе по кнопке.
   */
  public synchronized String join(String path) {
    requireApp();
    Found found =
        sites().stream()
            .filter(s -> s.path().equals(path))
            .findFirst()
            .orElseThrow(() -> new Problem("Такой папки сайта не найдено"));
    SiteFolder f = new SiteFolder(Path.of(found.path()));
    List<String> names = f.snapshotNames();
    if (names.isEmpty()) {
      throw new Problem("В папке ещё нет данных сайта — подождите, пока облако их докачает");
    }
    String newest = names.getLast();
    try {
      SiteSnapshot.ready(f, newest);
    } catch (SiteSnapshot.Incomplete e) {
      throw new Problem(
          e.getMessage()
              + (e.total > 1 ? " (" + e.have + " из " + e.total + ")" : "")
              + " — подождите пару минут и попробуйте снова");
    } catch (IOException e) {
      throw new Problem("Не удалось прочитать данные сайта: " + e.getMessage());
    }
    folder = f;
    cfg.setDir(f.dir());
    cfg.computerId();
    cfg.computerName();
    cfg.setPending(newest, false);
    saveCfg();
    restartSoon();
    return found.name();
  }

  public void requireApp() {
    if (cfg == null || !bridge.enabled()) {
      throw new Problem("Работа на нескольких компьютерах — только в приложении хоста");
    }
  }

  List<CloudFolders.Folder> roots() {
    List<CloudFolders.Folder> out = new ArrayList<>();
    if (testRoot != null) {
      out.add(new CloudFolders.Folder("Тестовое облако", testRoot));
    }
    out.addAll(CloudFolders.detect());
    return out;
  }

  private Path chosenRoot(String path) {
    if (path == null || path.isBlank()) {
      throw new Problem("Выберите облачный диск");
    }
    Path p = Path.of(path).toAbsolutePath().normalize();
    return roots().stream()
        .map(CloudFolders.Folder::path)
        .filter(r -> r.toAbsolutePath().normalize().equals(p))
        .findFirst()
        .orElseThrow(() -> new Problem("Такого облачного диска на этом компьютере нет"));
  }

  private String cloudLabel(Path dir) {
    for (CloudFolders.Folder f : roots()) {
      if (dir.startsWith(f.path().toAbsolutePath().normalize())) {
        return f.label();
      }
    }
    return null;
  }

  // ---------- жизненный цикл ----------

  @Override
  public synchronized void start() {
    if (running || cfg == null) {
      return;
    }
    running = true;
    lastTick = clock.millis();
    if (folder != null && role == Role.SWITCHING && cfg.pending().isBlank()) {
      // Снова запустили после остановки (в тестах контекст ставят на паузу) — решаем заново.
      decide(clock.millis(), true);
    }
    timer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("hosts").factory());
    timer.scheduleWithFixedDelay(this::tick, tickMs, tickMs, TimeUnit.MILLISECONDS);
  }

  /**
   * Остановка (выход, перезапуск, обновление): хост сохраняет последний снимок и отмечает, что
   * закрыт — другой компьютер сможет сразу взять сайт. При перезапуске и обновлении — «скоро
   * вернусь», чтобы сайт не уехал на другой компьютер на эти полминуты.
   */
  @Override
  public void stop() {
    ScheduledExecutorService t;
    synchronized (this) {
      running = false;
      t = timer;
      timer = null;
    }
    if (t != null) {
      t.shutdownNow();
      try {
        t.awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    synchronized (this) {
      if (role != Role.HOST || folder == null) {
        return;
      }
      role = Role.SWITCHING;
      long now = clock.millis();
      try {
        String fp = fingerprint();
        Snap s = fp.equals(fingerprint) ? lastSnapshot : snapshot(now, fp);
        boolean back = bridge.restartRequested() || bridge.updating();
        folder.writeHost(record(back ? SiteFolder.RESTARTING : SiteFolder.STOPPED, now, s, null));
      } catch (IOException | RuntimeException e) {
        log.warn("Не удалось отметить остановку в облачной папке: {}", e.getMessage());
      }
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** Раньше остальных: пока идёт последний снимок, сервер ещё принимает запросы окна. */
  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 10;
  }
}
