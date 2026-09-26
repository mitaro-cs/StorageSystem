package app.groupbase.hosts;

import app.groupbase.accounts.PublicUrl;
import app.groupbase.backup.BackupService;
import app.groupbase.backup.PendingRestore;
import app.groupbase.config.GroupbaseProperties;
import app.groupbase.desktop.DesktopBridge;
import app.groupbase.desktop.DesktopConfig;
import jakarta.servlet.http.HttpServletResponse;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Перенос сайта на другой компьютер по коду — без файла копии и без облачного диска.
 *
 * <ul>
 *   <li>На компьютере, где сайт работает (или с телефона администратора), берут код: одноразовый,
 *       на 15 минут.
 *   <li>Новый компьютер по адресу сайта и коду скачивает копию (как резервную — с ключами и входом
 *       CloudPub), проверяет её и подтверждает перенос.
 *   <li>Пока копия едет, изменения на сайте не принимаются (читать можно); после подтверждения
 *       прежний компьютер останавливает сайт насовсем, а новый перезапускается уже с данными —
 *       адрес тот же.
 * </ul>
 *
 * Не подтвердили за 10 минут — сайт снова принимает изменения, как будто переноса не было.
 */
@Service
public class TransferService {

  private static final Logger log = LoggerFactory.getLogger(TransferService.class);

  static final Duration CODE_TTL = Duration.ofMinutes(15);
  static final Duration CONFIRM_TTL = Duration.ofMinutes(10);
  static final Duration SEND_LOCK = Duration.ofMinutes(30);
  static final int MAX_WRONG = 5;

  /** Без похожих знаков (0/O, 1/I): код диктуют и переписывают с экрана. */
  private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

  /**
   * Код для экрана хоста.
   *
   * @param phase waiting — ждём другой компьютер, sending — он скачивает, sent — скачал, ждём
   *     подтверждения
   */
  public record CodeView(String code, long expiresAt, String url, String phase, long sent) {}

  /**
   * Как идёт перенос сюда: download, check, confirm, restart или error (с причиной).
   *
   * @param total сколько всего (оценка прежнего компьютера), 0 — неизвестно
   */
  public record PullView(String phase, long received, long total, String error) {}

  private final HostService hosts;
  private final BackupService backups;
  private final PublicUrl publicUrl;
  private final GroupbaseProperties props;
  private final DesktopBridge bridge;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  // ---- этот компьютер отдаёт сайт ----
  private String code;
  private long expiresAt;
  private int wrong;
  private String phase = "waiting";
  private volatile long sent;
  private String confirmed;

  // ---- этот компьютер забирает сайт ----
  private volatile PullView pull = new PullView("idle", 0, 0, null);

  public TransferService(
      HostService hosts,
      BackupService backups,
      PublicUrl publicUrl,
      GroupbaseProperties props,
      DesktopBridge bridge,
      Clock clock) {
    this.hosts = hosts;
    this.backups = backups;
    this.publicUrl = publicUrl;
    this.props = props;
    this.bridge = bridge;
    this.clock = clock;
  }

  // ---------- отдать ----------

  /** Новый код (прежний перестаёт действовать). */
  public synchronized CodeView newCode() {
    hosts.requireServing();
    if ("sending".equals(phase) || ("sent".equals(phase) && code != null)) {
      throw new HostService.Problem("Другой компьютер уже забирает сайт — дождитесь или отмените");
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 12; i++) {
      sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    code = sb.toString();
    expiresAt = clock.millis() + CODE_TTL.toMillis();
    wrong = 0;
    phase = "waiting";
    sent = 0;
    return view();
  }

  /** Текущий код и как идёт перенос; null — кода нет. */
  public synchronized CodeView status() {
    expire();
    return code == null ? null : view();
  }

  public synchronized void cancel() {
    code = null;
    if (!"sending".equals(phase)) {
      phase = "waiting";
      hosts.lockWrites(0);
    }
  }

  private CodeView view() {
    return new CodeView(
        code.substring(0, 4) + "-" + code.substring(4, 8) + "-" + code.substring(8),
        expiresAt,
        publicUrl.get().orElse(null),
        phase,
        sent);
  }

  /** Код устарел, копию так и не подтвердили — сайт снова принимает изменения. */
  private void expire() {
    if (code != null && clock.millis() > expiresAt && !"sending".equals(phase)) {
      code = null;
      phase = "waiting";
      hosts.lockWrites(0);
    }
  }

  private void check(String given) {
    expire();
    if (code == null) {
      throw new HostService.Problem(
          "Код устарел или не выдавался — возьмите новый на компьютере, где работает сайт");
    }
    String g = normalize(given);
    if (!MessageDigest.isEqual(
        g.getBytes(StandardCharsets.US_ASCII), code.getBytes(StandardCharsets.US_ASCII))) {
      if (++wrong >= MAX_WRONG) {
        // Подбирают — код больше не действует.
        code = null;
        phase = "waiting";
        hosts.lockWrites(0);
      }
      throw new HostService.Problem("Неверный код переноса");
    }
  }

  static String normalize(String given) {
    return given == null ? "" : given.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
  }

  /**
   * Отдать копию другому компьютеру (он пришёл с кодом). Пока она едет и пока её не подтвердили,
   * изменения не принимаются — иначе они остались бы только здесь.
   */
  public void send(String given, HttpServletResponse res) throws IOException {
    synchronized (this) {
      check(given);
      phase = "sending";
      sent = 0;
      hosts.lockWrites(clock.millis() + SEND_LOCK.toMillis());
    }
    log.info("Другой компьютер забирает сайт по коду");
    try {
      res.setContentType("application/zip");
      res.setHeader("Cache-Control", "no-store");
      res.setHeader("X-Groupbase-Size", String.valueOf(estimate()));
      res.setHeader("X-Groupbase-Port", String.valueOf(props.http().port()));
      OutputStream counted =
          new FilterOutputStream(res.getOutputStream()) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
              out.write(b, off, len);
              sent += len;
            }
          };
      backups.write(counted);
      counted.flush();
      synchronized (this) {
        long now = clock.millis();
        phase = "sent";
        expiresAt = Math.max(expiresAt, now + CONFIRM_TTL.toMillis());
        hosts.lockWrites(now + CONFIRM_TTL.toMillis());
      }
    } catch (IOException | RuntimeException e) {
      synchronized (this) {
        phase = "waiting";
        hosts.lockWrites(0);
      }
      throw e;
    }
  }

  /**
   * Новый компьютер проверил копию и подтверждает: здесь сайт останавливается. Не сразу — сначала
   * должен уйти ответ (через туннель, который сейчас выключится).
   */
  public synchronized void confirm(String given) {
    if (confirmed != null && confirmed.equals(normalize(given))) {
      return; // повтор после обрыва связи
    }
    check(given);
    if (!"sent".equals(phase)) {
      throw new HostService.Problem("Сначала данные должны скачаться");
    }
    confirmed = code;
    code = null;
    phase = "waiting";
    CompletableFuture.delayedExecutor(1500, TimeUnit.MILLISECONDS).execute(hosts::movedAway);
  }

  /** Сколько примерно весит копия — для полоски на другом компьютере. */
  private long estimate() {
    long total = 0;
    for (Path p :
        List.of(
            props.databaseFile(),
            props.filesDir(),
            props.dataDir().resolve("avatars"),
            props.secretsDir())) {
      if (Files.isRegularFile(p)) {
        try {
          total += Files.size(p);
        } catch (IOException e) {
          // не страшно — оценка
        }
      } else if (Files.isDirectory(p)) {
        try (Stream<Path> walk = Files.walk(p)) {
          total +=
              walk.filter(Files::isRegularFile)
                  .mapToLong(
                      f -> {
                        try {
                          return Files.size(f);
                        } catch (IOException e) {
                          return 0;
                        }
                      })
                  .sum();
        } catch (IOException e) {
          // не страшно — оценка
        }
      }
    }
    return total;
  }

  // ---------- забрать ----------

  public PullView pullStatus() {
    return pull;
  }

  /**
   * Забрать сайт сюда по адресу и коду: скачать копию, проверить, подтвердить и перезапуститься —
   * данные встанут при запуске, до того как сервер откроет базу. Прежние данные этого компьютера не
   * удаляются — откладываются в before-restore-….
   *
   * @return что показать, пока приложение перезапускается
   */
  public String pull(String siteUrl, String given) {
    hosts.requireApp();
    synchronized (this) {
      if (List.of("download", "check", "confirm", "restart").contains(pull.phase())) {
        throw new HostService.Problem("Перенос уже идёт");
      }
      pull = new PullView("download", 0, 0, null);
    }
    Path data = props.dataDir();
    Path zip = data.resolve("restore").resolve("transfer-" + clock.millis() + ".zip");
    boolean staged = false;
    try {
      TransferClient client = new TransferClient(siteUrl);
      TransferClient.Got got =
          client.download(
              given, zip, p -> pull = new PullView("download", p.received(), p.total(), null));
      long size = Files.size(zip);
      pull = new PullView("check", size, size, null);
      try {
        SiteFolder.verify(zip);
      } catch (IOException e) {
        throw new IOException("Копия пришла не целиком — попробуйте ещё раз", e);
      }
      PendingRestore.stage(zip, data);
      staged = true;
      pull = new PullView("confirm", size, size, null);
      confirmOrCheck(client, given);
      hosts.prepareForPull();
      if (got.port() > 0) {
        // Адрес в CloudPub ведёт на порт прежнего компьютера — по возможности тот же.
        DesktopConfig.load(data).prefer(got.port());
      }
      pull = new PullView("restart", size, size, null);
      log.info("Сайт забран с другого компьютера по коду — перезапуск");
      if (bridge.enabled()) {
        CompletableFuture.delayedExecutor(700, TimeUnit.MILLISECONDS)
            .execute(bridge::requestRestart);
      }
      return "Данные сайта получены — перезапускаемся…";
    } catch (IOException | RuntimeException e) {
      if (staged) {
        try {
          // Перенос не подтвердили — копию не применять, иначе сайт заработает в двух местах.
          Files.deleteIfExists(PendingRestore.file(data));
        } catch (IOException ignored) {
          // при следующем запуске всё равно возьмётся — но подтверждения не было; см. журнал
          log.warn("Не удалось убрать неподтверждённую копию переноса");
        }
      }
      String why =
          e instanceof HostService.Problem || e instanceof IOException
              ? e.getMessage()
              : "Не удалось перенести сайт: " + e.getMessage();
      pull = new PullView("error", 0, 0, why);
      throw new HostService.Problem(why);
    } finally {
      try {
        Files.deleteIfExists(zip);
      } catch (IOException ignored) {
        // временный файл — уберётся с каталогом restore
      }
    }
  }

  /**
   * Подтверждение с повторами. Если ответ так и не пришёл, смотрим, отвечает ли ещё прежний
   * компьютер: молчит — значит, подтверждение дошло и сайт он уже остановил (туннель выключен).
   */
  private void confirmOrCheck(TransferClient client, String given) throws IOException {
    IOException last = null;
    for (int i = 0; i < 3; i++) {
      try {
        client.confirm(given);
        return;
      } catch (IOException e) {
        last = e;
        if (!e.getMessage().startsWith("Не удалось связаться")) {
          throw e; // ответ пришёл, но с отказом — не переносим
        }
      }
      sleep(2000);
    }
    sleep(5000);
    if (client.serverAnswers()) {
      throw new IOException(
          "Не получилось подтвердить перенос — сайт остался на прежнем компьютере. Попробуйте ещё"
              + " раз",
          last);
    }
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
