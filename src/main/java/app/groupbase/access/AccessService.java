package app.groupbase.access;

import app.groupbase.accounts.InstanceSettings;
import app.groupbase.accounts.Names;
import app.groupbase.accounts.PublicUrl;
import app.groupbase.auth.Tokens;
import app.groupbase.config.GroupbaseProperties;
import app.groupbase.config.Secrets;
import app.groupbase.desktop.DesktopBridge;
import app.groupbase.desktop.DesktopConfig;
import app.groupbase.store.SettingsStore;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Доступ к сайту для участников, когда сервер работает на компьютере хоста.
 *
 * <ul>
 *   <li><b>fxtunnel</b> — бесплатный туннель fxTunnel с адресом {@code имя.fxtun.ru}: HTTPS,
 *       работает из России, без белого IP. Хост один раз входит в fxTunnel через браузер, сервер
 *       сам скачивает клиент и держит туннель открытым, переподключаясь при обрывах.
 *   <li><b>lan</b> — только локальная сеть (одна Wi-Fi), по HTTP: без установки на телефон и
 *       офлайн-режима.
 *   <li><b>manual</b> — свой адрес (домен, VPS, другой туннель): сервер только знает его для
 *       ссылок.
 * </ul>
 *
 * Если адрес задан в groupbase.toml ({@code base-url}), доступом управляет администратор сервера, и
 * здесь ничего не включается.
 */
@Service
public class AccessService {

  private static final Logger log = LoggerFactory.getLogger(AccessService.class);

  public enum Mode {
    OFF,
    FXTUNNEL,
    LAN,
    MANUAL;

    public String id() {
      return name().toLowerCase(Locale.ROOT);
    }

    static Mode of(String s) {
      try {
        return valueOf(s.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        return OFF;
      }
    }
  }

  public enum State {
    OFF,
    STARTING,
    ONLINE,
    RETRYING,
    ERROR,
    NEEDS_LOGIN;

    public String id() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public record Login(String url, String code, long expiresAt) {}

  public record Fx(
      boolean loggedIn, Login login, String subdomain, String suggested, String domain) {}

  public record Lan(boolean available, List<String> urls) {}

  public record View(
      String mode,
      String state,
      String message,
      String url,
      boolean fixed,
      boolean desktop,
      Fx fxtunnel,
      Lan lan,
      String manualUrl) {}

  /** Ошибка, понятная пользователю: текст уходит в интерфейс. */
  public static final class Problem extends RuntimeException {
    public final String field;

    Problem(String field, String message) {
      super(message);
      this.field = field;
    }
  }

  static final String MODE = "access.mode";
  static final String FX_TOKEN = "access.fxtunnel.token";
  static final String FX_SUBDOMAIN = "access.fxtunnel.subdomain";
  static final String FX_SUGGESTED = "access.fxtunnel.suggested";
  static final String MANUAL_URL = "access.manual.url";
  private static final String TOKEN_CONTEXT = "fxtunnel-token";

  public static final String FX_DOMAIN = "fxtun.ru";
  static final String FX_VERSION = "v3.12.0";
  private static final Map<String, String> FX_SHA256 =
      Map.of(
          "darwin-amd64", "3d52e7c4c982ca0ccef29d8022e46f734b0dfc7298661138e5d29f7418b821e2",
          "darwin-arm64", "324622e119c0ebb09b8ca1bd3fd1c62e1346c57b4446a323b8ab6d7f8d6bec10",
          "linux-amd64", "6708d1ccc43ac2f628dc82e07ede4c0c92bc4fca7b948fdd70bf73c9d6a7dff9",
          "linux-arm64", "0afeda5d2e03a7a5ce32408a44a1b8a762609aa7a66a78ee82c5383dbf10cf7a",
          "windows-amd64", "b0ba043d6a86271244b430573f3d61d6184ab129d32951126fa77bc6ebf25b2a");
  static final Pattern SUBDOMAIN = Pattern.compile("[a-z0-9](?:[a-z0-9-]{1,30}[a-z0-9])");
  private static final Pattern HTTPS_URL = Pattern.compile("HTTPS:\\s*(https://\\S+)");
  private static final Pattern FAILED = Pattern.compile("(?i)failed to connect:\\s*(.*)");
  private static final Pattern AUTH_ERROR =
      Pattern.compile("(?i)(unauthori[sz]ed|invalid token|token (is )?(invalid|expired)|401)");
  private static final Pattern TAKEN_ERROR =
      Pattern.compile("(?i)(domain|subdomain).*(taken|in use|already|unavailable|reserved)");
  private static final Pattern LIMIT_ERROR = Pattern.compile("(?i)(limit|too many tunnels)");
  private static final long[] BACKOFF_SEC = {5, 15, 30, 60, 120};

  private final SettingsStore settings;
  private final Secrets secrets;
  private final PublicUrl publicUrl;
  private final DesktopBridge bridge;
  private final GroupbaseProperties props;
  private final InstanceSettings instance;
  private final Clock clock;
  private final FxTunnelApi api;
  private final Path fxBinaryOverride;
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(
          r -> Thread.ofPlatform().daemon().name("access-timer").unstarted(r));

  private volatile State state = State.OFF;
  private volatile String message;
  private volatile Login login;
  private TunnelProcess process;

  /** Номер запуска: вывод и завершение старого процесса после остановки игнорируются. */
  private long gen;

  private String lastError;
  private int failures;
  private ScheduledFuture<?> retry;

  public AccessService(
      SettingsStore settings,
      Secrets secrets,
      PublicUrl publicUrl,
      DesktopBridge bridge,
      GroupbaseProperties props,
      InstanceSettings instance,
      Clock clock,
      Environment env) {
    this.settings = settings;
    this.secrets = secrets;
    this.publicUrl = publicUrl;
    this.bridge = bridge;
    this.props = props;
    this.instance = instance;
    this.clock = clock;
    // Для тестов: подставной клиент и API.
    this.api =
        new FxTunnelApi(
            URI.create(env.getProperty("groupbase.access.fxtunnel-api", "https://fxtun.ru")));
    String bin = env.getProperty("groupbase.access.fxtunnel-bin", "");
    this.fxBinaryOverride = bin.isBlank() ? null : Path.of(bin);
  }

  // ---------- состояние ----------

  public Mode mode() {
    return publicUrl.fixed() ? Mode.MANUAL : Mode.of(settings.get(MODE).orElse("off"));
  }

  public synchronized View view() {
    Mode mode = mode();
    State shown =
        switch (mode) {
          case OFF -> State.OFF;
          case LAN, MANUAL -> State.ONLINE;
          case FXTUNNEL -> token() == null ? State.NEEDS_LOGIN : state;
        };
    return new View(
        mode.id(),
        shown.id(),
        shown == State.NEEDS_LOGIN && message == null
            ? "Войдите в fxTunnel, чтобы открыть доступ"
            : message,
        publicUrl.get().orElse(null),
        publicUrl.fixed(),
        bridge.enabled(),
        new Fx(
            token() != null,
            loginActive(),
            settings.get(FX_SUBDOMAIN).filter(s -> !s.isBlank()).orElse(null),
            suggested(),
            FX_DOMAIN),
        new Lan(bridge.enabled(), lanUrls()),
        settings.get(MANUAL_URL).filter(s -> !s.isBlank()).orElse(null));
  }

  @EventListener(ApplicationReadyEvent.class)
  public synchronized void onReady() {
    Mode mode = mode();
    if (publicUrl.fixed()) {
      announce();
      return;
    }
    switch (mode) {
      case FXTUNNEL -> {
        String sub = settings.get(FX_SUBDOMAIN).orElse("");
        publicUrl.set(fxUrl(sub));
        if (token() == null) {
          setState(State.NEEDS_LOGIN, null);
        } else {
          start();
        }
      }
      case LAN -> {
        if (bridge.enabled() && lanOn()) {
          publicUrl.set(lanUrls().stream().findFirst().orElse(""));
        } else {
          settings.set(MODE, Mode.OFF.id());
          publicUrl.set("");
        }
      }
      case MANUAL -> publicUrl.set(settings.get(MANUAL_URL).orElse(""));
      case OFF -> publicUrl.set("");
    }
    announce();
  }

  // ---------- режимы ----------

  public synchronized View enableFxTunnel(String subdomain) {
    requireManaged();
    String sub = normalize(subdomain);
    if (token() == null) {
      throw new Problem("login", "Сначала войдите в fxTunnel");
    }
    boolean wasLan = lanOn();
    settings.set(FX_SUBDOMAIN, sub);
    settings.set(MODE, Mode.FXTUNNEL.id());
    publicUrl.set(fxUrl(sub));
    if (wasLan) {
      switchLan(false);
    } else {
      start();
    }
    announce();
    return view();
  }

  public synchronized View enableManual(String url) {
    requireManaged();
    String u = url == null ? "" : url.strip().replaceAll("/+$", "");
    URI parsed;
    try {
      parsed = URI.create(u);
    } catch (IllegalArgumentException e) {
      throw new Problem("url", "Это не похоже на адрес сайта");
    }
    if (parsed.getHost() == null
        || !(u.startsWith("https://") || u.startsWith("http://"))
        || parsed.getRawPath() != null && !parsed.getRawPath().isEmpty()) {
      throw new Problem("url", "Адрес вида https://example.ru — без пути после домена");
    }
    stopTunnel();
    boolean wasLan = lanOn();
    settings.set(MANUAL_URL, u);
    settings.set(MODE, Mode.MANUAL.id());
    publicUrl.set(u);
    setState(State.ONLINE, null);
    if (wasLan) {
      switchLan(false);
    }
    announce();
    return view();
  }

  /** Только локальная сеть: сервер начнёт слушать сеть, поэтому приложение его перезапустит. */
  public synchronized View enableLan() {
    requireManaged();
    if (!bridge.enabled()) {
      throw new Problem("mode", "Режим локальной сети есть только в приложении для компьютера");
    }
    stopTunnel();
    settings.set(MODE, Mode.LAN.id());
    publicUrl.set(lanUrls().stream().findFirst().orElse(""));
    setState(State.ONLINE, null);
    announce();
    if (!lanOn()) {
      switchLan(true);
    }
    return view();
  }

  public synchronized View disable() {
    requireManaged();
    stopTunnel();
    boolean wasLan = lanOn();
    settings.set(MODE, Mode.OFF.id());
    publicUrl.set("");
    setState(State.OFF, null);
    announce();
    if (wasLan) {
      switchLan(false);
    }
    return view();
  }

  private void requireManaged() {
    if (publicUrl.fixed()) {
      throw new Problem(
          "mode", "Адрес сайта задан в настройках сервера (base-url) — меняйте его там");
    }
  }

  // ---------- fxTunnel: вход ----------

  /** Начать вход в fxTunnel: код показывается в интерфейсе, подтверждение — на сайте fxTunnel. */
  public synchronized View startLogin() {
    requireManaged();
    Login current = loginActive();
    if (current != null) {
      return view();
    }
    FxTunnelApi.DeviceCode dc;
    try {
      dc = api.startLogin();
    } catch (IOException e) {
      throw new Problem("login", "Не удалось связаться с fxTunnel. Проверьте интернет");
    }
    long expires = clock.millis() + dc.expiresInSec() * 1000;
    login = new Login(dc.authUrl(), dc.userCode(), expires);
    Thread.ofVirtual().name("fxtunnel-login").start(() -> pollLogin(dc.session(), expires));
    return view();
  }

  private void pollLogin(String session, long expires) {
    while (clock.millis() < expires) {
      try {
        Thread.sleep(2000);
        FxTunnelApi.DeviceToken t = api.poll(session);
        if ("authorized".equals(t.status()) && t.token() != null) {
          synchronized (this) {
            saveToken(t.token());
            login = null;
            message = null;
            if (mode() == Mode.FXTUNNEL) {
              start();
            } else {
              setState(State.OFF, null);
            }
          }
          return;
        }
        if ("expired".equals(t.status())) {
          break;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (IOException e) {
        // Сеть мигнула — спросим ещё раз.
      }
    }
    synchronized (this) {
      if (login != null && login.expiresAt() <= expires) {
        login = null;
        message = "Код входа истёк — нажмите «Войти в fxTunnel» ещё раз";
      }
    }
  }

  private Login loginActive() {
    Login l = login;
    return l != null && l.expiresAt() > clock.millis() ? l : null;
  }

  public synchronized View logout() {
    requireManaged();
    stopTunnel();
    settings.set(FX_TOKEN, "");
    login = null;
    setState(mode() == Mode.FXTUNNEL ? State.NEEDS_LOGIN : State.OFF, null);
    announce();
    return view();
  }

  /** Свободен ли адрес в fxTunnel (если вход уже выполнен). */
  public FxTunnelApi.Check check(String subdomain) {
    String sub = normalize(subdomain);
    String token = token();
    if (token == null) {
      return new FxTunnelApi.Check(true, "");
    }
    try {
      return api.check(token, sub);
    } catch (IOException e) {
      // Проверка — подсказка, а не условие: fxTunnel всё равно скажет при подключении.
      return new FxTunnelApi.Check(true, "");
    }
  }

  static String normalize(String subdomain) {
    String sub = subdomain == null ? "" : subdomain.strip().toLowerCase(Locale.ROOT);
    if (!SUBDOMAIN.matcher(sub).matches()) {
      throw new Problem(
          "subdomain", "Адрес — от 3 до 32 латинских букв, цифр и дефисов, без дефиса по краям");
    }
    return sub;
  }

  /** Предложение адреса: название группы латиницей и случайный хвост — чтобы не угадали. */
  String suggested() {
    return settings
        .get(FX_SUGGESTED)
        .filter(s -> !s.isBlank())
        .orElseGet(
            () -> {
              String base = Names.slug(instance.name().isBlank() ? "group" : instance.name());
              base = base.length() > 20 ? base.substring(0, 20) : base;
              String tail = Tokens.newToken().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
              String s = (base + "-" + tail.substring(0, 5)).replaceAll("^-+|-+$", "");
              settings.set(FX_SUGGESTED, s);
              return s;
            });
  }

  private String token() {
    String sealed = settings.get(FX_TOKEN).orElse("");
    if (sealed.isBlank()) {
      return null;
    }
    try {
      return new String(
          secrets.open(Base64.getDecoder().decode(sealed), TOKEN_CONTEXT), StandardCharsets.UTF_8);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private void saveToken(String token) {
    settings.set(
        FX_TOKEN,
        Base64.getEncoder()
            .encodeToString(secrets.seal(token.getBytes(StandardCharsets.UTF_8), TOKEN_CONTEXT)));
  }

  // ---------- fxTunnel: процесс ----------

  private synchronized void start() {
    stopTunnel();
    String token = token();
    String sub = settings.get(FX_SUBDOMAIN).orElse("");
    if (token == null || sub.isBlank()) {
      setState(State.NEEDS_LOGIN, null);
      return;
    }
    setState(State.STARTING, "Подключаемся к fxTunnel…");
    Thread.ofVirtual().name("fxtunnel-start").start(() -> launch(token, sub));
  }

  private void launch(String token, String sub) {
    long myGen;
    synchronized (this) {
      myGen = gen;
    }
    Path bin;
    try {
      bin = binary();
    } catch (IOException e) {
      synchronized (this) {
        scheduleRetry("Не удалось скачать клиент fxTunnel: " + e.getMessage());
      }
      return;
    }
    synchronized (this) {
      if (myGen != gen
          || mode() != Mode.FXTUNNEL
          || state != State.STARTING && state != State.RETRYING) {
        return;
      }
      lastError = null;
      List<String> cmd =
          List.of(
              bin.toString(),
              "http",
              String.valueOf(props.http().port()),
              "--domain",
              sub,
              "--no-inspect");
      try {
        process =
            TunnelProcess.start(
                cmd,
                Map.of("FXTUNNEL_TOKEN", token),
                props.toolsDir().resolve("fxtunnel").resolve("run.pid"),
                line -> onLine(myGen, line),
                code -> onExit(myGen, code));
        timer.schedule(() -> slowStart(myGen), 45, TimeUnit.SECONDS);
      } catch (IOException e) {
        scheduleRetry("Не удалось запустить клиент fxTunnel: " + e.getMessage());
      }
    }
  }

  private Path binary() throws IOException {
    if (fxBinaryOverride != null) {
      return fxBinaryOverride;
    }
    Platform p = Platform.current();
    String key = p.os() + "-" + p.arch();
    String sha = FX_SHA256.get(key);
    if (sha == null) {
      throw new IOException("для этой системы нет сборки клиента");
    }
    Path dir = props.toolsDir().resolve("fxtunnel");
    Path target = dir.resolve(p.exe("fxtunnel"));
    if (!java.nio.file.Files.isRegularFile(target)) {
      setMessage("Скачиваем клиент fxTunnel…");
    }
    String asset = "fxtunnel-" + key + (p.windows() ? ".exe" : "");
    return new Tool(
            "клиент fxTunnel",
            URI.create(
                "https://github.com/mephistofox/fxtun.dev/releases/download/"
                    + FX_VERSION
                    + "/"
                    + asset),
            sha)
        .ensure(target);
  }

  private synchronized void slowStart(long myGen) {
    if (myGen == gen && state == State.STARTING) {
      message = "fxTunnel долго не отвечает — проверьте интернет. Продолжаем пытаться…";
    }
  }

  private synchronized void onLine(long myGen, String line) {
    if (myGen != gen || line.isEmpty()) {
      return;
    }
    log.debug("fxtunnel: {}", line);
    Matcher url = HTTPS_URL.matcher(line);
    if (url.find()) {
      failures = 0;
      String got = url.group(1).replaceAll("/+$", "");
      if (!got.equals(publicUrl.get().orElse(""))) {
        publicUrl.set(got);
      }
      setState(State.ONLINE, null);
      announce();
      return;
    }
    Matcher failed = FAILED.matcher(line);
    if (failed.find()) {
      lastError = failed.group(1);
    } else if (line.toLowerCase(Locale.ROOT).contains("error")) {
      lastError = line;
    }
  }

  private synchronized void onExit(long myGen, int code) {
    if (myGen != gen) {
      return;
    }
    process = null;
    log.info("Клиент fxTunnel завершился (код {})", code);
    if (mode() != Mode.FXTUNNEL) {
      return;
    }
    String err = lastError == null ? "" : lastError;
    if (AUTH_ERROR.matcher(err).find()) {
      settings.set(FX_TOKEN, "");
      setState(State.NEEDS_LOGIN, "Вход в fxTunnel устарел — войдите заново");
    } else if (TAKEN_ERROR.matcher(err).find()) {
      failures = BACKOFF_SEC.length - 1;
      scheduleRetry(
          "Адрес занят другим пользователем fxTunnel. Выберите другой — или подождите, пока он"
              + " освободится");
    } else if (LIMIT_ERROR.matcher(err).find()) {
      scheduleRetry(
          "С этого аккаунта fxTunnel уже открыт другой туннель. Закройте его — подключимся сами");
    } else {
      scheduleRetry(
          err.isBlank()
              ? "Связь с fxTunnel прервалась — переподключаемся"
              : "fxTunnel: " + err + " — переподключаемся");
    }
    announce();
  }

  private void scheduleRetry(String why) {
    long delay = BACKOFF_SEC[Math.min(failures, BACKOFF_SEC.length - 1)];
    failures++;
    setState(State.RETRYING, why);
    if (retry != null) {
      retry.cancel(false);
    }
    retry =
        timer.schedule(
            () -> {
              synchronized (this) {
                if (mode() == Mode.FXTUNNEL && state == State.RETRYING) {
                  String token = token();
                  String sub = settings.get(FX_SUBDOMAIN).orElse("");
                  if (token == null) {
                    setState(State.NEEDS_LOGIN, null);
                    return;
                  }
                  Thread.ofVirtual().name("fxtunnel-start").start(() -> launch(token, sub));
                }
              }
            },
            delay,
            TimeUnit.SECONDS);
  }

  private void stopTunnel() {
    gen++;
    if (retry != null) {
      retry.cancel(false);
      retry = null;
    }
    if (process != null) {
      process.stop();
      process = null;
    }
    failures = 0;
  }

  @PreDestroy
  synchronized void shutdown() {
    stopTunnel();
    timer.shutdownNow();
  }

  private void setState(State s, String msg) {
    state = s;
    message = msg;
  }

  private void setMessage(String msg) {
    message = msg;
  }

  /** Оболочке приложения — адрес для пункта «Скопировать ссылку» и состояние. */
  private void announce() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("url", publicUrl.get().orElse(""));
    m.put("state", view().state());
    bridge.event("access", m);
  }

  // ---------- локальная сеть ----------

  private boolean lanOn() {
    if (!bridge.enabled()) {
      return false;
    }
    try {
      return DesktopConfig.load(props.dataDir()).lan();
    } catch (IOException e) {
      return false;
    }
  }

  private void switchLan(boolean on) {
    try {
      DesktopConfig.load(props.dataDir()).setLan(on);
    } catch (IOException e) {
      throw new Problem("mode", "Не удалось сохранить настройку сети: " + e.getMessage());
    }
    // Ответ уйдёт раньше, чем оболочка перезапустит сервер.
    timer.schedule(bridge::requestRestart, 700, TimeUnit.MILLISECONDS);
  }

  /** Адреса этого компьютера в локальной сети: http://192.168.…:порт. */
  List<String> lanUrls() {
    List<String> out = new ArrayList<>();
    try {
      for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || ni.isPointToPoint()) {
          continue;
        }
        for (InetAddress a : Collections.list(ni.getInetAddresses())) {
          if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
            out.add("http://" + a.getHostAddress() + ":" + props.http().port());
          }
        }
      }
    } catch (SocketException e) {
      // Сетевых интерфейсов не видно — адресов нет.
    }
    // Обычно нужный — домашний Wi-Fi 192.168.x.x.
    out.sort((a, b) -> Boolean.compare(!a.contains("//192.168."), !b.contains("//192.168.")));
    return out;
  }

  static String fxUrl(String sub) {
    return sub.isBlank() ? "" : "https://" + sub + "." + FX_DOMAIN;
  }
}
