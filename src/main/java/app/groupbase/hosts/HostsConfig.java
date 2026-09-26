package app.groupbase.hosts;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Этот компьютер среди компьютеров хоста: его имя, общая папка сайта и чьи данные сейчас лежат в
 * каталоге. Файл {@code hosts.properties} в каталоге данных — у каждого компьютера свой, в копии и
 * снимки он не попадает.
 */
public final class HostsConfig {

  public static final String FILE = "hosts.properties";

  private final Path file;
  private final Properties p;

  private HostsConfig(Path file, Properties p) {
    this.file = file;
    this.p = p;
  }

  public static HostsConfig load(Path dataDir) throws IOException {
    Path f = dataDir.resolve(FILE);
    Properties p = new Properties();
    if (Files.isRegularFile(f)) {
      try (InputStream in = Files.newInputStream(f)) {
        p.load(in);
      }
    }
    return new HostsConfig(f, p);
  }

  /** Постоянный номер этого компьютера (каталога данных); появляется при первом включении. */
  public synchronized String computerId() {
    String id = p.getProperty("computer.id", "");
    if (id.isBlank()) {
      id = UUID.randomUUID().toString();
      p.setProperty("computer.id", id);
    }
    return id;
  }

  /** Как называть этот компьютер: «MacBook Air», «DESKTOP-1A2B3C» — или как назвал хост. */
  public synchronized String computerName() {
    String name = p.getProperty("computer.name", "");
    if (name.isBlank()) {
      name = defaultName();
      p.setProperty("computer.name", name);
    }
    return name;
  }

  public synchronized void setComputerName(String name) {
    p.setProperty("computer.name", name);
  }

  /** Общая папка сайта; null — этот компьютер не подключён. */
  public synchronized Path dir() {
    String d = p.getProperty("dir", "");
    return d.isBlank() ? null : Path.of(d);
  }

  public synchronized void setDir(Path dir) {
    p.setProperty("dir", dir == null ? "" : dir.toAbsolutePath().normalize().toString());
  }

  /** Поколение данных в каталоге: растёт, когда сайт переходит на другой компьютер. */
  public synchronized long epoch() {
    return number("epoch");
  }

  /** Снимок, с которым совпадают данные в каталоге (записан или взят здесь). */
  public synchronized String snapshot() {
    return p.getProperty("snapshot", "");
  }

  public synchronized void setData(long epoch, String snapshot) {
    p.setProperty("epoch", String.valueOf(epoch));
    p.setProperty("snapshot", snapshot == null ? "" : snapshot);
  }

  public synchronized void setEpoch(long epoch) {
    p.setProperty("epoch", String.valueOf(epoch));
  }

  /** После переноса сюда по просьбе хоста — при запуске стать хостом, не спрашивая. */
  public synchronized boolean claim() {
    return Boolean.parseBoolean(p.getProperty("claim", "false"));
  }

  public synchronized void setClaim(boolean claim) {
    p.setProperty("claim", String.valueOf(claim));
  }

  /** Снимок, который нужно взять при следующем запуске (до того, как сервер откроет базу). */
  public synchronized String pending() {
    return p.getProperty("pending", "");
  }

  public synchronized boolean pendingClaim() {
    return Boolean.parseBoolean(p.getProperty("pending.claim", "false"));
  }

  public synchronized void setPending(String snapshot, boolean claim) {
    p.setProperty("pending", snapshot == null ? "" : snapshot);
    p.setProperty("pending.claim", String.valueOf(claim && snapshot != null));
  }

  /** Почему не удалось взять данные при запуске — показывается на странице ожидания. */
  public synchronized String error() {
    return p.getProperty("error", "");
  }

  public synchronized void setError(String error) {
    p.setProperty("error", error == null ? "" : error);
  }

  /**
   * Снимок, который эта версия программы взять не смогла (например, он из более новой версии), —
   * чтобы не перезапускаться ради него снова и снова. После обновления программы пробуем опять.
   */
  public synchronized String failed(String version) {
    return version.equals(p.getProperty("failed.version", "")) ? p.getProperty("failed", "") : "";
  }

  public synchronized void setFailed(String snapshot, String version) {
    p.setProperty("failed", snapshot == null ? "" : snapshot);
    p.setProperty("failed.version", snapshot == null ? "" : version);
  }

  private long number(String key) {
    try {
      return Long.parseLong(p.getProperty(key, "0").strip());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  public synchronized void save() throws IOException {
    Files.createDirectories(file.getParent());
    Path tmp = file.resolveSibling(FILE + ".tmp");
    try (OutputStream out = Files.newOutputStream(tmp)) {
      p.store(out, "groupbase: этот компьютер среди компьютеров хоста");
    }
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  /** Имя компьютера из системы; без обращения к DNS (на Mac оно может ждать секунды). */
  static String defaultName() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String name = null;
    if (os.contains("win")) {
      name = System.getenv("COMPUTERNAME");
    } else if (os.contains("mac")) {
      name = run("scutil", "--get", "ComputerName");
    } else {
      try {
        name = Files.readString(Path.of("/etc/hostname")).strip();
      } catch (IOException e) {
        name = null;
      }
    }
    if (name == null || name.isBlank()) {
      name = os.contains("mac") ? "Mac" : os.contains("win") ? "Компьютер с Windows" : "Компьютер";
    }
    name = name.strip();
    return name.length() > 40 ? name.substring(0, 40) : name;
  }

  private static String run(String... cmd) {
    try {
      Process pr = new ProcessBuilder(cmd).redirectErrorStream(true).start();
      pr.getOutputStream().close();
      if (!pr.waitFor(2, TimeUnit.SECONDS)) {
        pr.destroyForcibly();
        return null;
      }
      String out = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
      return pr.exitValue() == 0 ? out : null;
    } catch (IOException e) {
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }
}
