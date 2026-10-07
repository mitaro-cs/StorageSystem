package app.groupbase.hosts;

import app.groupbase.access.AccessService;
import app.groupbase.accounts.PublicUrl;
import app.groupbase.auth.Tokens;
import app.groupbase.backup.BackupService;
import app.groupbase.backup.PendingRestore;
import app.groupbase.config.GroupbaseProperties;
import app.groupbase.desktop.DesktopBridge;
import app.groupbase.store.SettingsStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Два компьютера хоста напрямую, без облачной папки (0.8, просьба владельца): дома — ПК, в вузе —
 * ноутбук, и на обоих в окне приложения можно работать.
 *
 * <ul>
 *   <li>Адрес сайта для группы ведёт на <b>главный</b> компьютер. Второй держит полную копию: раз в
 *       несколько секунд спрашивает у главного (по тому же адресу сайта), что нового, и забирает
 *       снимок базы и недостающие файлы; свои файлы, которых нет у главного, отдаёт ему.
 *   <li>Изменение в окне второго компьютера сразу уходит главному, а копия обновляется до ответа
 *       окну. Нет связи — изменение применяется здесь же и ждёт в очереди; связь появилась — уходит
 *       главному, и копия выравнивается по нему.
 *   <li>Главный пропал (по адресу сайта отвечает страница туннеля, а интернет у второго есть)
 *       дольше {@link #TAKEOVER_MS} — второй становится главным: поколение растёт, туннель
 *       поднимается здесь. Вернувшийся компьютер видит главного старшего поколения и сам становится
 *       вторым: свои изменения без связи отдаёт новому главному, а свою базу на всякий случай
 *       откладывает в {@code peer/aside}.
 * </ul>
 *
 * Сопряжение — кодом с экрана главного (как перенос по коду). У каждого компьютера свой ключ:
 * открыт он только в его {@code hosts.properties}, а в базе (таблица {@code host_peers}, едет в
 * копиях) — хеш, поэтому проверить другого может любой из них, когда станет главным.
 */
@Service
public class PeerService implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(PeerService.class);

  public enum Role {
    OFF,
    MAIN,
    SECOND;

    public String id() {
      return name().toLowerCase(Locale.ROOT);
    }

    static Role of(String s) {
      return switch (s == null ? "" : s) {
        case "main" -> MAIN;
        case "second" -> SECOND;
        default -> OFF;
      };
    }
  }

  static final long TICK_MS = 5_000;
  static final long PROBE_MS = 30_000;
  static final long FILES_MS = 60_000;
  static final long TAKEOVER_MS = 60_000;
  static final Duration CODE_TTL = Duration.ofMinutes(15);
  static final int MAX_WRONG = 5;
  static final int KEEP_ASIDE = 3;
  private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

  public record Code(String code, long expiresAt, String url) {}

  /** Ответ главного на пересланное изменение — окну как есть. */
  public record Reply(int status, String contentType, byte[] body) {}

  public record Peer(String computerId, String name, long createdAt, Long seenAt, boolean here) {}

  /**
   * Что показать в «Управление → Сервер».
   *
   * @param state ok, offline (нет связи), nobody (главный не отвечает), behind (версии разные),
   *     checking (главный проверяет, не работает ли сайт на другом компьютере), conflict (этот
   *     считает себя основным, а по адресу сайта отвечает другой – тоже основной)
   * @param rival имя компьютера, который отвечает по адресу сайта при conflict
   * @param nobodyFor сколько главный уже не отвечает (мс) — до перехода сюда
   * @param serving имя компьютера, на котором сайт сейчас работает для группы (null — неизвестно)
   */
  public record View(
      boolean available,
      String role,
      String state,
      String message,
      String computer,
      String url,
      long epoch,
      Long syncedAt,
      int queued,
      int filesMissing,
      long nobodyFor,
      String serving,
      String rival,
      boolean cloud,
      List<Peer> peers,
      Code code,
      List<Request> requests,
      Ask ask) {}

  /** Ошибка, понятная пользователю. */
  public static final class Problem extends RuntimeException {
    Problem(String message) {
      super(message);
    }
  }

  private final GroupbaseProperties props;
  private final JdbcClient db;
  private final DataSource dataSource;
  private final SettingsStore settings;
  private final HostService hosts;
  private final AccessService access;
  private final BackupService backups;
  private final DesktopBridge bridge;
  private final PublicUrl publicUrl;
  private final Clock clock;
  private final long tickMs;
  private final long takeoverMs;
  private final SecureRandom random = new SecureRandom();
  private final HostsConfig cfg;
  private final PeerQueue queue;

  /** Данные второго компьютера меняются либо снимком главного, либо изменением без связи. */
  private final ReentrantLock dataLock = new ReentrantLock();

  /** Шаги синхронизации — по одному (фоновый и после изменения не тянут снимок одновременно). */
  private final ReentrantLock stepLock = new ReentrantLock();

  private volatile Role role = Role.OFF;
  private volatile String state = "ok";
  private volatile String message;
  private volatile Long syncedAt;
  private volatile int filesMissing;
  private volatile long nobodySince;
  private volatile long lastProbe;
  private volatile long lastFiles;
  private volatile boolean checking;

  /** На каком компьютере сайт, по последнему ответу адреса сайта (у копии). */
  private volatile String holder;

  /** Главный убедился, что адрес сайта ведёт сюда: журнал изменений без связи не нужен. */
  private volatile boolean confirmedHere;

  /** По адресу сайта отвечает другой основной (поколение не старше нашего) – его номер. */
  private volatile String rival;

  private final Map<String, Long> seen = new ConcurrentHashMap<>();

  private String code;
  private long codeExpires;
  private int wrong;

  private ScheduledExecutorService timer;
  private volatile boolean running;

  public PeerService(
      GroupbaseProperties props,
      JdbcClient db,
      DataSource dataSource,
      SettingsStore settings,
      HostService hosts,
      AccessService access,
      BackupService backups,
      DesktopBridge bridge,
      PublicUrl publicUrl,
      Clock clock,
      Environment env) {
    this.props = props;
    this.db = db;
    this.dataSource = dataSource;
    this.settings = settings;
    this.hosts = hosts;
    this.access = access;
    this.backups = backups;
    this.bridge = bridge;
    this.publicUrl = publicUrl;
    this.clock = clock;
    // Для тестов: шаги — по команде теста, переход — без ожидания.
    this.tickMs = env.getProperty("groupbase.peers.tick-ms", Long.class, TICK_MS);
    this.takeoverMs = env.getProperty("groupbase.peers.takeover-ms", Long.class, TAKEOVER_MS);
    this.cfg = hosts.config();
    this.queue = new PeerQueue(props.dataDir());
    if (cfg != null) {
      role = Role.of(cfg.peerRole());
    }
    // До того как AccessService поднимет туннель: второй компьютер его не поднимает, а главный —
    // только убедившись, что сайт не работает на другом компьютере (тот мог стать главным, пока
    // этот был выключен).
    if (role == Role.SECOND) {
      access.suspend(SECOND_MESSAGE);
    } else if (role == Role.MAIN) {
      checking = true;
      state = "checking";
      access.suspend("Проверяем, не работает ли сайт на другом компьютере…");
    }
    // «Разрешить» в диалоге оболочки приходит командой в stdin.
    bridge.onPeerAnswer(
        (id, allow) -> {
          try {
            answer(id, allow);
          } catch (Problem e) {
            log.info("Ответ на запрос подключения не применён: {}", e.getMessage());
          }
        });
  }

  static final String SECOND_MESSAGE =
      "Сайт для группы сейчас работает на другом компьютере, здесь – его копия.";

  public Role role() {
    return role;
  }

  /** Изменения в окне этого компьютера пересылаются главному. */
  public boolean second() {
    return role == Role.SECOND;
  }

  /**
   * Главный без подтверждённой связи: изменения окна ещё и записываются (вдруг главный уже другой).
   */
  public boolean journaling() {
    return role == Role.MAIN && !confirmedHere;
  }

  PeerQueue queue() {
    return queue;
  }

  // ---------- главный: сопряжение ----------

  /** Код для второго компьютера; этот компьютер становится главным, если ещё не был. */
  public synchronized Code newCode() {
    requireApp();
    if (hosts.cloudEnabled()) {
      throw new Problem(
          "Включён перенос через облачную папку – выключите его, чтобы связать компьютеры"
              + " напрямую");
    }
    if (role == Role.SECOND) {
      throw new Problem(
          "Код показывает компьютер, на котором сейчас сайт, – или нажмите здесь «Перенести сайт сюда»");
    }
    try {
      // https — или адрес в локальной сети: по нему второй компьютер найдёт этот.
      TransferClient.normalize(publicUrl.get().orElse(""));
    } catch (IOException e) {
      throw new Problem(
          "Сначала включите доступ для группы по адресу https://… – по нему другой компьютер"
              + " найдёт этот");
    }
    if (role == Role.OFF) {
      becomeMain(Math.max(1, cfg.peerEpoch()));
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 12; i++) {
      sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    code = sb.toString();
    codeExpires = clock.millis() + CODE_TTL.toMillis();
    wrong = 0;
    return codeView();
  }

  public synchronized Code code() {
    if (code != null && clock.millis() > codeExpires) {
      code = null;
    }
    return code == null ? null : codeView();
  }

  private Code codeView() {
    return new Code(
        code.substring(0, 4) + "-" + code.substring(4, 8) + "-" + code.substring(8),
        codeExpires,
        publicUrl.get().orElse(null));
  }

  /** Второй компьютер пришёл с кодом: выдать ему ключ. */
  public synchronized Map<String, Object> pair(String given, String computerId, String name) {
    if (role != Role.MAIN) {
      throw new Problem("Сайт сейчас не на этом компьютере – возьмите код там, где он работает");
    }
    if (keyMatches(given)) {
      return issue(computerId, name);
    }
    if (code == null || clock.millis() > codeExpires) {
      code = null;
      throw new Problem("Код устарел или не выдавался – возьмите новый");
    }
    String g = TransferService.normalize(given);
    if (!MessageDigest.isEqual(
        g.getBytes(StandardCharsets.US_ASCII), code.getBytes(StandardCharsets.US_ASCII))) {
      if (++wrong >= MAX_WRONG) {
        code = null;
      }
      throw new Problem("Неверный код");
    }
    code = null;
    return issue(computerId, name);
  }

  /** Выдать компьютеру ключ (код или ключ сайта уже проверены). */
  private Map<String, Object> issue(String computerId, String name) {
    String id = computerId == null ? "" : computerId.strip();
    if (!id.matches("[0-9a-fA-F-]{8,64}") || id.equals(cfg.computerId())) {
      throw new Problem("Не похоже на компьютер Campus – обновите приложение там");
    }
    String token = Tokens.newToken();
    savePeer(id, cleanName(name), token);
    log.info("Второй компьютер хоста подключён");
    return Map.of(
        "token", token, "site", siteId(), "computer", cfg.computerId(), "epoch", cfg.peerEpoch());
  }

  public synchronized void removePeer(String computerId) {
    requireApp();
    if (computerId == null || computerId.equals(cfg.computerId())) {
      throw new Problem("Этот компьютер отключить отсюда нельзя");
    }
    db.sql("DELETE FROM host_peers WHERE computer_id = ?").param(computerId).update();
    seen.remove(computerId);
  }

  private void savePeer(String computerId, String name, String token) {
    db.sql(
            """
            INSERT INTO host_peers (computer_id, name, token_hash, created_at) VALUES (?, ?, ?, ?)
            ON CONFLICT (computer_id) DO UPDATE SET name = excluded.name,
              token_hash = excluded.token_hash
            """)
        .params(computerId, name, Tokens.sha256(token), clock.millis())
        .update();
  }

  private static String cleanName(String name) {
    String n = name == null ? "" : name.strip();
    if (n.isEmpty()) {
      n = "Компьютер";
    }
    return n.length() > 40 ? n.substring(0, 40) : n;
  }

  private String siteId() {
    return settings
        .get(HostService.SITE_ID)
        .filter(s -> !s.isBlank())
        .orElseGet(
            () -> {
              String id = UUID.randomUUID().toString();
              settings.set(HostService.SITE_ID, id);
              return id;
            });
  }

  /**
   * Чей это запрос: «номер ключ» в заголовке {@value PeerClient#PEER}. Ключ проверяется по хешу из
   * базы.
   */
  public Optional<String> authenticate(String header) {
    if (header == null || role == Role.OFF) {
      return Optional.empty();
    }
    int space = header.indexOf(' ');
    if (space <= 0) {
      return Optional.empty();
    }
    String id = header.substring(0, space);
    String token = header.substring(space + 1).strip();
    if (!Tokens.looksValid(token)) {
      return Optional.empty();
    }
    Optional<byte[]> hash =
        db.sql("SELECT token_hash FROM host_peers WHERE computer_id = ?")
            .param(id)
            .query(byte[].class)
            .optional();
    if (hash.isEmpty() || !MessageDigest.isEqual(hash.get(), Tokens.sha256(token))) {
      return Optional.empty();
    }
    seen.put(id, clock.millis());
    return Optional.of(id);
  }

  // ---------- главный: что отдаёт второму ----------

  /** Отвечать второму может только главный (сайт работает здесь). */
  public void requireMain() {
    if (role != Role.MAIN) {
      throw new Problem("Сайт сейчас работает не на этом компьютере");
    }
  }

  public Map<String, Object> state() {
    requireMain();
    return Map.of(
        "site",
        siteId(),
        "computer",
        cfg.computerId(),
        "epoch",
        cfg.peerEpoch(),
        "seq",
        seq(),
        "schema",
        schema(),
        "moveTo",
        moveTo());
  }

  /** Полная копия (как резервная, с ключами и файлами) — для первого подключения второго. */
  public void writeCopy(OutputStream out) throws IOException {
    requireMain();
    backups.write(out);
  }

  /** Согласованный снимок базы, сжатый. */
  public void writeDb(OutputStream out) throws IOException {
    requireMain();
    snapshotTo(out);
  }

  private void snapshotTo(OutputStream out) throws IOException {
    Path dir = props.dataDir().resolve("peer");
    Files.createDirectories(dir);
    Path snap =
        dir.resolve("out-" + clock.millis() + "-" + Thread.currentThread().threadId() + ".db");
    Files.deleteIfExists(snap);
    try {
      db.sql("VACUUM INTO ?").param(snap.toAbsolutePath().toString()).update();
      GZIPOutputStream gz = new GZIPOutputStream(out, 64 * 1024);
      Files.copy(snap, gz);
      gz.finish();
      gz.flush();
    } finally {
      Files.deleteIfExists(snap);
    }
  }

  /** Файлы и аватары этого компьютера: «files/…», «avatars/…». */
  public List<String> files() throws IOException {
    List<String> out = new ArrayList<>();
    for (String top : List.of("files", "avatars")) {
      Path root = props.dataDir().resolve(top);
      if (!Files.isDirectory(root)) {
        continue;
      }
      try (Stream<Path> walk = Files.walk(root)) {
        for (Path p : walk.filter(Files::isRegularFile).toList()) {
          String rel = root.relativize(p).toString().replace('\\', '/');
          if (!rel.endsWith(".part") && !rel.endsWith(".tmp")) {
            out.add(top + "/" + rel);
          }
        }
      }
    }
    return out;
  }

  /** Путь файла по имени из списка — только внутри files/ и avatars/. */
  public Path file(String name) {
    String n = name == null ? "" : name.replace('\\', '/');
    if (!(n.startsWith("files/") || n.startsWith("avatars/"))
        || n.contains("..")
        || !n.matches("[A-Za-z0-9._/-]{3,200}")) {
      throw new Problem("Недопустимое имя файла");
    }
    Path data = props.dataDir().toAbsolutePath().normalize();
    Path p = data.resolve(n).normalize();
    if (!p.startsWith(data.resolve(n.substring(0, n.indexOf('/'))))) {
      throw new Problem("Недопустимое имя файла");
    }
    return p;
  }

  /**
   * Принять файл от второго компьютера (у главного его не было). Файлы не меняются — не затираем.
   */
  public void putFile(String name, InputStream in) throws IOException {
    requireMain();
    Path target = file(name);
    if (Files.exists(target)) {
      in.transferTo(OutputStream.nullOutputStream());
      return;
    }
    Files.createDirectories(target.getParent());
    Path part = target.resolveSibling(target.getFileName() + ".part");
    try (OutputStream out = Files.newOutputStream(part)) {
      in.transferTo(out);
    }
    Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
  }

  private long seq() {
    return db.sql("SELECT COALESCE(MAX(seq), 0) FROM changes").query(Long.class).single();
  }

  private int schema() {
    return db.sql(
            "SELECT COALESCE(MAX(CAST(version AS INTEGER)), 0) FROM flyway_schema_history"
                + " WHERE success = 1")
        .query(Integer.class)
        .single();
  }

  // ---------- ключ сайта: вставить один раз — компьютер связан навсегда ----------

  static final String KEY_SETTING = "peers.key";
  static final String KEY_PREFIX = "campus-";

  /**
   * Ключ сайта: адрес сайта и секрет одной строкой. Вставили на другом компьютере — тот сразу
   * подключается (без «Разрешить» и кодов) и дальше помнит свой ключ компьютера сам. Ключ живёт,
   * пока его не сменят; лежит в базе сайта — показать его можно на любом из связанных компьютеров.
   */
  public synchronized String siteKey() {
    requireApp();
    if (hosts.cloudEnabled()) {
      throw new Problem(
          "Включён перенос через облачную папку – выключите его, чтобы связать компьютеры"
              + " напрямую");
    }
    String url;
    try {
      url = TransferClient.normalize(publicUrl.get().orElse("")).toString();
    } catch (IOException e) {
      throw new Problem(
          "Сначала включите доступ для группы по адресу https://… – по нему другой компьютер"
              + " найдёт этот");
    }
    String secret =
        settings
            .get(KEY_SETTING)
            .filter(k -> !k.isBlank())
            .orElseGet(
                () -> {
                  String k = Tokens.newToken();
                  settings.set(KEY_SETTING, k);
                  return k;
                });
    if (role == Role.OFF) {
      becomeMain(Math.max(1, cfg.peerEpoch()));
    }
    String raw = url + "\n" + secret;
    return KEY_PREFIX
        + java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /** «Сменить ключ»: старый перестаёт подключать новые компьютеры (связанные остаются). */
  public synchronized String newSiteKey() {
    requireApp();
    settings.set(KEY_SETTING, Tokens.newToken());
    return siteKey();
  }

  private boolean keyMatches(String given) {
    String key = settings.get(KEY_SETTING).orElse("");
    return !key.isBlank()
        && given != null
        && MessageDigest.isEqual(
            given.strip().getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8));
  }

  /** Адрес сайта и секрет из ключа. */
  static String[] parseKey(String key) {
    String k = key == null ? "" : key.strip();
    if (!k.startsWith(KEY_PREFIX)) {
      throw new Problem("Это не ключ сайта – скопируйте его целиком в «Два компьютера»");
    }
    try {
      String raw =
          new String(
              java.util.Base64.getUrlDecoder().decode(k.substring(KEY_PREFIX.length())),
              StandardCharsets.UTF_8);
      int nl = raw.indexOf('\n');
      if (nl <= 0 || nl == raw.length() - 1) {
        throw new IllegalArgumentException();
      }
      return new String[] {raw.substring(0, nl), raw.substring(nl + 1)};
    } catch (IllegalArgumentException e) {
      throw new Problem("Ключ сайта повреждён – скопируйте его целиком ещё раз");
    }
  }

  /** Подключить этот компьютер ключом сайта: сразу, без подтверждения там. */
  public String joinByKey(String key) {
    String[] k = parseKey(key);
    return join(k[0], k[1]);
  }

  // ---------- подключение без ввода: запрос и подтверждение ----------

  static final Duration REQUEST_TTL = Duration.ofMinutes(3);
  static final int MAX_REQUESTS = 3;

  /** Запрос другого компьютера «подключиться»; ждёт ответа здесь. */
  private static final class Pending {
    final String secret;
    final String id;
    final String name;
    final long expires;
    volatile String status = "waiting";
    volatile String code;

    Pending(String secret, String id, String name, long expires) {
      this.secret = secret;
      this.id = id;
      this.name = name;
      this.expires = expires;
    }
  }

  /** Запрос в интерфейсе компьютера с сайтом (без секрета). */
  public record Request(String id, String name) {}

  /** Ответ на запрос: waiting, approved (с кодом связи), denied, expired. */
  public record Answer(String status, String code) {}

  private final Map<String, Pending> requests = new ConcurrentHashMap<>();

  /** Подключение здесь, на новом компьютере: idle, asking, waiting, joining, error. */
  public record Ask(String phase, String message, String url) {}

  private volatile Ask ask = new Ask("idle", null, null);

  /** Хост попросил другой компьютер стать хостом (выбор в окне хоста); до этого времени. */
  private volatile String moveTo;

  private volatile long moveToUntil;

  /**
   * Другой компьютер просит подключиться (знает только адрес сайта). Здесь появится вопрос
   * «Разрешить?» — в окне и диалогом оболочки; секрет запроса знает только тот компьютер.
   */
  public synchronized String request(String computerId, String name) {
    if (cfg == null || hosts.cloudEnabled() || role == Role.SECOND) {
      throw new Problem("Сайт сейчас работает не на этом компьютере");
    }
    String id = computerId == null ? "" : computerId.strip();
    if (!id.matches("[0-9a-fA-F-]{8,64}") || id.equals(cfg.computerId())) {
      throw new Problem("Не похоже на компьютер Campus – обновите приложение там");
    }
    long now = clock.millis();
    requests.values().removeIf(r -> r.expires < now);
    if (requests.size() >= MAX_REQUESTS) {
      throw new Problem("Слишком много запросов – подождите пару минут");
    }
    String secret = Tokens.newToken();
    String shortId = Tokens.newToken().replaceAll("[^A-Za-z0-9]", "").substring(0, 8);
    Pending p = new Pending(secret, shortId, cleanName(name), now + REQUEST_TTL.toMillis());
    requests.put(secret, p);
    bridge.event("peer-request", Map.of("id", p.id, "name", p.name));
    log.info("Другой компьютер просит подключиться к сайту");
    return secret;
  }

  /** Как ответили на запрос — спрашивает тот компьютер своим секретом. */
  public Answer answerFor(String secret) {
    Pending p = secret == null ? null : requests.get(secret);
    if (p == null || p.expires < clock.millis()) {
      if (p != null) {
        requests.remove(secret);
      }
      return new Answer("expired", null);
    }
    if (!"waiting".equals(p.status)) {
      requests.remove(secret);
    }
    return new Answer(p.status, p.code);
  }

  /** Запросы, которые ждут ответа здесь. */
  public List<Request> requests() {
    long now = clock.millis();
    return requests.values().stream()
        .filter(r -> r.expires >= now && "waiting".equals(r.status))
        .map(r -> new Request(r.id, r.name))
        .toList();
  }

  /** «Разрешить» или «Отклонить» — в окне или в диалоге оболочки. */
  public synchronized void answer(String id, boolean allow) {
    Pending p =
        requests.values().stream()
            .filter(r -> r.id.equals(id) && "waiting".equals(r.status))
            .findFirst()
            .orElseThrow(() -> new Problem("Запрос устарел – пусть тот компьютер попросит снова"));
    if (allow) {
      p.code = newCode().code();
      p.status = "approved";
      log.info("Подключение другого компьютера разрешено");
    } else {
      p.status = "denied";
    }
  }

  public Ask ask() {
    return ask;
  }

  /**
   * «Сделать хостом» в списке компьютеров — на любом из двух. Этот компьютер — сразу «Перенести
   * сайт сюда»; другой (а здесь хост) — тот увидит просьбу в следующем {@code /state} и заберёт
   * сайт сам, без потерь.
   */
  public View makeHost(String computerId) {
    requireApp();
    if (computerId == null || computerId.equals(cfg.computerId())) {
      return moveHere();
    }
    if (role != Role.MAIN) {
      throw new Problem(
          "Сайт сейчас не на этом компьютере – нажмите «Сделать хостом» на том, где он работает,"
              + " или на нужном компьютере");
    }
    boolean known =
        db.sql("SELECT COUNT(*) FROM host_peers WHERE computer_id = ?")
                .param(computerId)
                .query(Integer.class)
                .single()
            > 0;
    if (!known) {
      throw new Problem("Такого компьютера среди связанных нет");
    }
    moveTo = computerId;
    moveToUntil = clock.millis() + 2 * 60_000;
    log.info("Хостом станет другой компьютер – ждём, когда он заберёт сайт");
    return view();
  }

  private String moveTo() {
    return moveTo != null && clock.millis() < moveToUntil ? moveTo : "";
  }

  /**
   * Подключить этот компьютер к сайту, зная только его адрес: запрос туда, ждём «Разрешить» на том
   * компьютере, дальше — как по коду.
   */
  public synchronized Ask startAsk(String siteUrl) {
    requireApp();
    if (hosts.cloudEnabled()) {
      throw new Problem(
          "Выключите перенос через облачную папку – компьютеры будут связаны напрямую");
    }
    if (role != Role.OFF) {
      throw new Problem("Этот компьютер уже связан с другим");
    }
    if (List.of("asking", "waiting", "joining").contains(ask.phase())) {
      return ask;
    }
    String url;
    try {
      url = TransferClient.normalize(siteUrl).toString();
    } catch (IOException e) {
      throw new Problem(e.getMessage());
    }
    ask = new Ask("asking", null, url);
    Thread.ofVirtual().name("peer-ask").start(() -> runAsk(url));
    return ask;
  }

  private void runAsk(String url) {
    try {
      PeerClient c = new PeerClient(url, cfg.computerId(), "-");
      String secret = c.askToJoin(cfg.computerName());
      ask = new Ask("waiting", null, url);
      long deadline = clock.millis() + REQUEST_TTL.toMillis();
      while (clock.millis() < deadline) {
        Thread.sleep(1500);
        Answer a = c.answer(secret);
        switch (a.status()) {
          case "approved" -> {
            ask = new Ask("joining", null, url);
            String message = join(url, a.code());
            ask = new Ask("joining", message, url);
            return;
          }
          case "denied" -> {
            ask = new Ask("error", "На том компьютере отказали", url);
            return;
          }
          case "expired" -> {
            ask = new Ask("error", "Не дождались ответа – попробуйте ещё раз", url);
            return;
          }
          default -> {
            // ждём
          }
        }
      }
      ask = new Ask("error", "Не дождались ответа – попробуйте ещё раз", url);
    } catch (PeerClient.NoServer e) {
      ask = new Ask("error", "По этому адресу сейчас не отвечает Campus", url);
    } catch (IOException | Problem e) {
      ask = new Ask("error", e.getMessage(), url);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      ask = new Ask("error", "Прервано", url);
    }
  }

  /** Отвечает ли этот компьютер на поиск в локальной сети: здесь сайт, и у него есть адрес. */
  public Optional<Map<String, String>> discoverable() {
    if (cfg == null || role == Role.SECOND || hosts.cloudEnabled()) {
      return Optional.empty();
    }
    String url = publicUrl.get().orElse("");
    try {
      TransferClient.normalize(url);
    } catch (IOException e) {
      return Optional.empty();
    }
    return Optional.of(
        Map.of(
            "site", siteId(),
            "computer", cfg.computerId(),
            "name", cfg.computerName(),
            "url", url));
  }

  /** Этот компьютер: чтобы при поиске не находить себя. */
  public String computerId() {
    return cfg == null ? "" : cfg.computerId();
  }

  // ---------- второй: подключиться ----------

  /**
   * Подключить этот компьютер вторым: получить ключ по коду, скачать полную копию и перезапуститься
   * — данные встанут при запуске. Прежние данные этого компьютера не удаляются — откладываются в
   * before-restore-….
   */
  public String join(String siteUrl, String given) {
    requireApp();
    if (hosts.cloudEnabled()) {
      throw new Problem(
          "Выключите перенос через облачную папку – компьютеры будут связаны напрямую");
    }
    if (role != Role.OFF) {
      throw new Problem("Этот компьютер уже связан с другим");
    }
    Path data = props.dataDir();
    Path zip = data.resolve("restore").resolve("peer-" + clock.millis() + ".zip");
    try {
      PeerClient.Paired p = PeerClient.pair(siteUrl, given, cfg.computerId(), cfg.computerName());
      String url = TransferClient.normalize(siteUrl).toString();
      PeerClient client = new PeerClient(url, cfg.computerId(), p.token());
      client.download("/api/host/peer/copy", zip, false);
      try {
        SiteFolder.verify(zip);
      } catch (IOException e) {
        throw new IOException("Копия пришла не целиком – попробуйте ещё раз", e);
      }
      PendingRestore.stage(zip, data);
      synchronized (this) {
        cfg.setPeerRole(Role.SECOND.id());
        cfg.setPeerToken(p.token());
        cfg.setPeerUrl(url);
        cfg.setPeerEpoch(p.epoch());
        cfg.setPeerApplied(0);
        // Сайт с этого компьютера раньше перенесли по коду («moved») — теперь он снова в деле.
        cfg.setMoved(0);
        saveCfg();
        role = Role.SECOND;
        access.suspend(SECOND_MESSAGE);
      }
      queue.clear();
      log.info("Этот компьютер подключён вторым к сайту – перезапуск");
      if (bridge.enabled()) {
        CompletableFuture.delayedExecutor(700, TimeUnit.MILLISECONDS)
            .execute(bridge::requestRestart);
      }
      return "Данные сайта получены – перезапускаемся…";
    } catch (IOException e) {
      throw new Problem(e.getMessage());
    } finally {
      try {
        Files.deleteIfExists(zip);
      } catch (IOException ignored) {
        // временный файл — уберётся с каталогом restore
      }
    }
  }

  // ---------- шаги ----------

  /** Один шаг: второй — синхронизация и очередь, главный — проверка, что адрес сайта ведёт сюда. */
  void tick() {
    if (cfg == null) {
      return;
    }
    try {
      switch (role) {
        case SECOND -> secondStep(false);
        case MAIN -> mainStep(false);
        case OFF -> {}
      }
    } catch (RuntimeException e) {
      message = "Ошибка синхронизации: " + e.getMessage();
      log.warn("Шаг синхронизации компьютеров не удался: {}", e.getMessage());
    }
  }

  /** «Синхронизировать сейчас» и после изменения, пересланного главному. */
  public View syncNow() {
    requireApp();
    if (role == Role.SECOND) {
      secondStep(true);
    } else if (role == Role.MAIN) {
      mainStep(true);
    }
    return view();
  }

  private PeerClient client() throws IOException {
    String url = role == Role.SECOND ? cfg.peerUrl() : publicUrl.get().orElse("");
    return new PeerClient(url, cfg.computerId(), cfg.peerToken());
  }

  private void secondStep(boolean force) {
    stepLock.lock();
    try {
      secondStepLocked(force);
    } finally {
      stepLock.unlock();
    }
  }

  private void secondStepLocked(boolean force) {
    long now = clock.millis();
    try {
      PeerClient c = client();
      replay(c);
      PeerClient.State st = c.state();
      nobodySince = 0;
      holder = st.computer();
      if (cfg.computerId().equals(st.moveTo())) {
        // На хосте выбрали этот компьютер хостом — забираем сайт (moveHere берёт тот же замок).
        CompletableFuture.runAsync(
            () -> {
              try {
                moveHere();
              } catch (RuntimeException e) {
                message = e.getMessage();
              }
            });
        return;
      }
      if (st.computer().equals(cfg.computerId())) {
        return; // адрес сайта ведёт сюда — так быть не должно; подождём
      }
      if (!st.site().isBlank() && !st.site().equals(siteId())) {
        state = "behind";
        message = "Главный компьютер обслуживает другой сайт – подключите этот заново";
        return;
      }
      int mine = schema();
      if (st.schema() != mine) {
        state = "behind";
        message =
            st.schema() > mine
                ? "На другом компьютере Campus новее – обновите и этот"
                : "На этом компьютере Campus новее – обновите другой";
        return;
      }
      boolean pulled = false;
      if (force || st.epoch() != cfg.peerEpoch() || st.seq() != cfg.peerApplied()) {
        pull(c, st);
        pulled = true;
      }
      if (pulled || force || now - lastFiles >= FILES_MS) {
        reconcile(c);
        lastFiles = now;
      }
      state = "ok";
      message = null;
      syncedAt = now;
    } catch (PeerClient.NoServer e) {
      // Адрес сайта отвечает страницей туннеля: главный выключен, а интернет здесь есть.
      if (nobodySince == 0) {
        nobodySince = now;
      }
      holder = null;
      state = "nobody";
      message = "Сайт сейчас не отвечает – через минуту он заработает на этом компьютере";
      if (now - nobodySince >= takeoverMs) {
        takeOver("главный компьютер не отвечает");
      }
    } catch (IOException e) {
      nobodySince = 0;
      state = "offline";
      message = "Нет связи с сайтом – изменения подождут здесь";
    }
  }

  private void mainStep(boolean force) {
    stepLock.lock();
    try {
      mainStepLocked(force);
    } finally {
      stepLock.unlock();
    }
  }

  private void mainStepLocked(boolean force) {
    long now = clock.millis();
    if (!force && now - lastProbe < (checking ? TICK_MS : PROBE_MS)) {
      return;
    }
    lastProbe = now;
    try {
      PeerClient.State st = client().state();
      if (st.computer().equals(cfg.computerId())) {
        confirmedHere = true;
        rival = null;
        queue.clear();
        state = "ok";
        message = null;
        return;
      }
      if (st.epoch() >= cfg.peerEpoch()) {
        // Пока этот компьютер был без связи, главным стал другой.
        rival = null;
        demote(st);
        return;
      }
      // Оба считают себя основными, а группа ходит на другой (0.9.5: «на ПК тоже „основной“»).
      // Сам не уступаем – решает человек: «Оставить основным тот» или «Сделать основным этот».
      confirmedHere = false;
      rival = st.computer();
      state = "conflict";
      message = "По адресу сайта отвечает другой компьютер – он тоже считает себя основным";
    } catch (PeerClient.NoServer e) {
      // По адресу сайта никто не отвечает: сайт нигде не работает — поднимаем туннель здесь.
      confirmedHere = false;
      if (checking) {
        checking = false;
        state = "ok";
        message = null;
        access.resume();
        log.info("Сайт ни на одном компьютере не работает – главный поднимает туннель");
      }
    } catch (IOException e) {
      // Нет связи: туннель не поднимется и так; изменения окна записываются на всякий случай.
      confirmedHere = false;
      state = checking ? "checking" : "offline";
      message =
          checking
              ? "Нет интернета – сайт для группы откроется, когда связь появится"
              : "Нет связи с адресом сайта – изменения сохраняются и здесь";
    }
  }

  /** Отправить главному изменения из очереди — по порядку; при обрыве остановиться. */
  private void replay(PeerClient c) throws IOException {
    for (PeerQueue.Item i : queue.list()) {
      PeerClient.Reply r = c.forward(i.method(), i.uri(), i.contentType(), i.userId(), i.body());
      if (r.status() >= 400) {
        log.warn(
            "Изменение из очереди главный не принял ({}): {} {}",
            r.status(),
            i.method(),
            i.uri().replaceAll("\\?.*", ""));
      }
      queue.remove(i.id());
    }
  }

  /** Забрать снимок базы главного и подменить им здешнюю — с сохранением своих сессий. */
  private void pull(PeerClient c, PeerClient.State st) throws IOException {
    Path dir = props.dataDir().resolve("peer");
    Path incoming = dir.resolve("incoming.db");
    c.download("/api/host/peer/db", incoming, true);
    try {
      check(incoming);
      dataLock.lock();
      try {
        applySnapshot(incoming);
      } finally {
        dataLock.unlock();
      }
      cfg.setPeerEpoch(st.epoch());
      cfg.setPeerApplied(st.seq());
      saveCfg();
    } finally {
      Files.deleteIfExists(incoming);
    }
  }

  /** Снимок цел: открывается и проходит быструю проверку SQLite. */
  static void check(Path file) throws IOException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("PRAGMA quick_check")) {
      if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1))) {
        throw new IOException("Снимок базы пришёл повреждённым");
      }
    } catch (SQLException e) {
      throw new IOException("Снимок базы не открывается: " + e.getMessage(), e);
    }
  }

  /**
   * Подменить базу снимком ({@code restore from} — SQLite копирует страницы в открытую базу, сервер
   * не останавливается). Сессии этого компьютера (окно приложения, вход по сети) остаются.
   */
  void applySnapshot(Path snapshot) {
    List<Map<String, Object>> sessions = db.sql("SELECT * FROM sessions").query().listOfRows();
    try (Connection c = dataSource.getConnection();
        Statement st = c.createStatement()) {
      st.executeUpdate(
          "restore from '" + snapshot.toAbsolutePath().toString().replace("'", "''") + "'");
    } catch (SQLException e) {
      // База занята — попробуем на следующем шаге; это не обрыв связи.
      throw new Problem("Не удалось применить снимок базы: " + e.getMessage());
    }
    for (Map<String, Object> row : sessions) {
      List<String> cols = new ArrayList<>(row.keySet());
      String sql =
          "INSERT OR IGNORE INTO sessions ("
              + String.join(", ", cols)
              + ") SELECT "
              + String.join(", ", cols.stream().map(k -> "?").toList())
              + " WHERE EXISTS (SELECT 1 FROM users WHERE id = ?)";
      List<Object> params = new ArrayList<>();
      for (String k : cols) {
        params.add(row.get(k));
      }
      params.add(row.get("user_id"));
      try {
        db.sql(sql).params(params).update();
      } catch (RuntimeException e) {
        // столбцы разошлись (версии разные) — сессия просто пропадёт, войти можно снова
      }
    }
  }

  /** Файлы: недостающие — скачать, лишние (у главного нет) — отдать ему. */
  private void reconcile(PeerClient c) throws IOException {
    Set<String> remote = new HashSet<>(c.files());
    Set<String> local = new HashSet<>(files());
    int missing = 0;
    for (String name : remote) {
      if (local.contains(name)) {
        continue;
      }
      try {
        c.getFile(name, file(name));
      } catch (Problem | PeerClient.NoServer e) {
        missing++;
      } catch (IOException e) {
        missing++;
      }
    }
    for (String name : local) {
      if (remote.contains(name)) {
        continue;
      }
      try {
        c.putFile(name, file(name));
      } catch (IOException e) {
        missing++;
      }
    }
    filesMissing = missing;
  }

  // ---------- кто главный ----------

  private void becomeMain(long epoch) {
    cfg.setPeerRole(Role.MAIN.id());
    if (cfg.peerToken().isBlank()) {
      cfg.setPeerToken(Tokens.newToken());
    }
    cfg.setPeerEpoch(epoch);
    saveCfg();
    savePeer(cfg.computerId(), cfg.computerName(), cfg.peerToken());
    role = Role.MAIN;
    checking = false;
    confirmedHere = false;
  }

  /**
   * Второй становится главным: поколение растёт, номера журнала изменений уходят вперёд (телефоны
   * не примут новые изменения за уже полученные), туннель поднимается здесь.
   */
  synchronized void takeOver(String why) {
    if (role != Role.SECOND) {
      return;
    }
    becomeMain(cfg.peerEpoch() + 1);
    // Изменения из очереди здесь уже применены — эта база теперь главная.
    queue.clear();
    try {
      if (db.sql("UPDATE sqlite_sequence SET seq = seq + 1000 WHERE name = 'changes'").update()
          == 0) {
        db.sql("INSERT INTO sqlite_sequence (name, seq) VALUES ('changes', 1000)").update();
      }
    } catch (RuntimeException e) {
      log.warn("Не сдвинуть номера журнала изменений: {}", e.getMessage());
    }
    nobodySince = 0;
    state = "ok";
    message = null;
    access.resume();
    log.info("Этот компьютер стал главным: {}", why);
  }

  /**
   * Другой компьютер попросил сайт себе («Перенести сюда»): изменения здесь на миг замирают, этот
   * становится копией (свои новые изменения окна — в очередь, туннель — вниз), а снимок базы уходит
   * в ответ. Поколение у того компьютера вырастет, и этот дальше выравнивается по нему.
   */
  public long handOver(OutputStream out) throws IOException {
    long epoch;
    synchronized (this) {
      requireMain();
      hosts.lockWrites(clock.millis() + 30_000);
      access.suspend(SECOND_MESSAGE);
      cfg.setPeerRole(Role.SECOND.id());
      cfg.setPeerUrl(publicUrl.get().orElse(cfg.peerUrl()));
      cfg.setPeerApplied(-1);
      saveCfg();
      role = Role.SECOND;
      checking = false;
      confirmedHere = false;
      nobodySince = 0;
      moveTo = null;
      epoch = cfg.peerEpoch();
    }
    log.info("Сайт переезжает на другой компьютер по его просьбе – этот становится копией");
    try {
      // Запросы, начатые до переключения, успевают записаться и попасть в снимок.
      Thread.sleep(500);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    try {
      snapshotTo(out);
    } finally {
      hosts.lockWrites(0);
    }
    putAside();
    return epoch;
  }

  /**
   * «Перенести сайт сюда» — с любого из двух компьютеров. Тот, где сайт сейчас, отдаёт свежую базу
   * и становится копией; никто не отвечает — сайт просто поднимается здесь.
   */
  public View moveHere() {
    requireApp();
    if (role == Role.MAIN && rival != null) {
      // Два основных: сначала этот становится копией того, с кем работает группа (берёт его
      // данные), потом забирает сайт себе обычным переездом – ничего не теряется.
      yieldToRival();
    }
    if (role == Role.MAIN) {
      throw new Problem("Сайт и так работает на этом компьютере");
    }
    if (role != Role.SECOND) {
      throw new Problem("Сначала свяжите компьютеры");
    }
    stepLock.lock();
    Path incoming = props.dataDir().resolve("peer").resolve("handover.db");
    try {
      if (role != Role.SECOND) {
        // Пока ждали замка, сайт уже переехал сюда (второй вызов того же переезда).
        return view();
      }
      PeerClient c = client();
      replay(c);
      long epoch;
      try {
        epoch = c.handover(incoming);
      } catch (PeerClient.NoServer e) {
        takeOver("перенос сюда, другой компьютер не отвечал");
        return view();
      }
      try {
        check(incoming);
        dataLock.lock();
        try {
          applySnapshot(incoming);
        } finally {
          dataLock.unlock();
        }
      } catch (IOException | Problem e) {
        // Тот компьютер уже отдал сайт: поднимаем здесь с последней копией, его база — в
        // peer/aside там.
        log.warn("Снимок при переезде не применился: {}", e.getMessage());
      }
      cfg.setPeerEpoch(Math.max(cfg.peerEpoch(), epoch));
      takeOver("перенос сюда");
      return view();
    } catch (IOException e) {
      throw new Problem(
          "Нет связи с компьютером, где сейчас сайт: " + e.getMessage() + ". Попробуйте ещё раз");
    } finally {
      stepLock.unlock();
      try {
        Files.deleteIfExists(incoming);
      } catch (IOException ignored) {
        // уберётся при следующем переезде
      }
    }
  }

  /**
   * Главным стал другой компьютер (этот был без связи): база этого — в сторону, изменения без связи
   * — новому главному, дальше этот — второй.
   */
  private synchronized void demote(PeerClient.State st) {
    if (role != Role.MAIN) {
      return;
    }
    log.info("Главным стал другой компьютер – этот становится вторым");
    access.suspend(SECOND_MESSAGE);
    putAside();
    cfg.setPeerRole(Role.SECOND.id());
    cfg.setPeerUrl(publicUrl.get().orElse(cfg.peerUrl()));
    cfg.setPeerApplied(-1);
    saveCfg();
    role = Role.SECOND;
    checking = false;
    confirmedHere = false;
    secondStep(true);
  }

  /**
   * Копия базы перед тем, как её заменит снимок нового главного (последние {@value #KEEP_ASIDE}).
   */
  private void putAside() {
    Path dir = props.dataDir().resolve("peer").resolve("aside");
    try {
      Files.createDirectories(dir);
      db.sql("VACUUM INTO ?")
          .param(
              dir.resolve("before-" + clock.millis() + "-" + UUID.randomUUID() + ".db")
                  .toAbsolutePath()
                  .toString())
          .update();
      try (Stream<Path> files = Files.list(dir)) {
        List<Path> all = files.filter(f -> f.toString().endsWith(".db")).sorted().toList();
        for (int i = 0; i < all.size() - KEEP_ASIDE; i++) {
          Files.deleteIfExists(all.get(i));
        }
      }
    } catch (IOException | RuntimeException e) {
      log.warn("Не удалось отложить копию базы перед переходом: {}", e.getMessage());
    }
  }

  // ---------- изменения окна ----------

  /** Ответ главного на пересланное изменение; null — нет связи (изменение остаётся здесь). */
  public Reply forward(String method, String uri, String contentType, long userId, Path body) {
    try {
      PeerClient c = client();
      PeerClient.Reply r = c.forward(method, uri, contentType, userId, body);
      if (r.status() == 409
          && new String(r.body(), StandardCharsets.UTF_8).contains("\"not_main\"")) {
        // По адресу сайта отвечает компьютер, который сейчас не главный, — как без связи.
        return null;
      }
      if (r.status() < 400) {
        // Окно сразу увидит своё изменение: копия обновляется до ответа.
        try {
          secondStep(true);
        } catch (RuntimeException e) {
          log.warn("Копия не обновилась после изменения: {}", e.getMessage());
        }
      }
      return new Reply(r.status(), r.contentType(), r.body());
    } catch (IOException e) {
      return null;
    }
  }

  /** Записать изменение в очередь (тело переносится в очередь). */
  public void enqueue(String method, String uri, String contentType, long userId, Path body)
      throws IOException {
    queue.add(method, uri, contentType, userId, clock.millis(), body);
  }

  public Path tempBody() throws IOException {
    return queue.tempBody();
  }

  /** Применить изменение здесь, пока снимок главного не подменяет базу. */
  public void locally(Runnable apply) {
    dataLock.lock();
    try {
      apply.run();
    } finally {
      dataLock.unlock();
    }
  }

  // ---------- для интерфейса ----------

  public View view() {
    boolean available = cfg != null;
    List<Peer> peers = List.of();
    if (available && role != Role.OFF) {
      peers =
          db.sql("SELECT computer_id, name, created_at FROM host_peers ORDER BY created_at")
              .query(
                  (rs, i) -> {
                    String id = rs.getString(1);
                    return new Peer(
                        id,
                        rs.getString(2),
                        rs.getLong(3),
                        seen.get(id),
                        id.equals(cfg.computerId()));
                  })
              .list();
    }
    long now = clock.millis();
    return new View(
        available,
        role.id(),
        state,
        message,
        available ? cfg.computerName() : null,
        role == Role.SECOND ? cfg.peerUrl() : publicUrl.get().orElse(null),
        available ? cfg.peerEpoch() : 0,
        syncedAt,
        queue.size(),
        filesMissing,
        nobodySince == 0 ? 0 : now - nobodySince,
        !available ? null : role == Role.MAIN ? cfg.computerName() : nameOf(peers, holder),
        rival == null || role != Role.MAIN ? null : nameOrId(peers, rival),
        available && hosts.cloudEnabled(),
        peers,
        role == Role.MAIN ? code() : null,
        available ? requests() : List.of(),
        ask);
  }

  private static String nameOrId(List<Peer> peers, String computerId) {
    String n = nameOf(peers, computerId);
    return n == null ? "другой компьютер" : n;
  }

  /**
   * «Оставить основным тот»: этот компьютер при conflict уступает – становится копией того, кто
   * отвечает по адресу сайта (его база важнее: группа работала с ним).
   */
  public View yieldToRival() {
    requireApp();
    stepLock.lock();
    try {
      if (role != Role.MAIN) {
        throw new Problem("Этот компьютер и так не основной");
      }
      PeerClient.State st;
      try {
        st = client().state();
      } catch (IOException e) {
        throw new Problem("Нет связи с адресом сайта – проверьте интернет и попробуйте ещё раз");
      }
      if (st.computer().equals(cfg.computerId())) {
        rival = null;
        state = "ok";
        message = null;
        throw new Problem("По адресу сайта отвечает этот компьютер – он и есть основной");
      }
      rival = null;
      demote(st);
    } finally {
      stepLock.unlock();
    }
    return view();
  }

  private static String nameOf(List<Peer> peers, String computerId) {
    return peers.stream()
        .filter(p -> p.computerId().equals(computerId))
        .map(Peer::name)
        .findFirst()
        .orElse(null);
  }

  private void requireApp() {
    if (cfg == null) {
      throw new Problem("Это доступно только в приложении хоста на компьютере");
    }
  }

  private void saveCfg() {
    try {
      cfg.save();
    } catch (IOException e) {
      log.warn("Не сохранить настройки компьютера хоста: {}", e.getMessage());
    }
  }

  // ---------- жизненный цикл ----------

  @Override
  public synchronized void start() {
    running = true;
    if (cfg == null || tickMs <= 0) {
      return;
    }
    timer =
        Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("peers-", 0).factory());
    timer.scheduleWithFixedDelay(this::tick, 2_000, tickMs, TimeUnit.MILLISECONDS);
  }

  @Override
  public synchronized void stop() {
    running = false;
    if (timer != null) {
      timer.shutdownNow();
      timer = null;
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
