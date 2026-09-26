package app.groupbase.hosts;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Общая папка сайта на облачном диске — {@code <диск>/groupbase-site/<номер сайта>/}. Через неё
 * компьютеры хоста передают друг другу сайт:
 *
 * <ul>
 *   <li>{@code site.json} — какой это сайт (название — для выбора на новом компьютере);
 *   <li>{@code host.json} — кто сейчас хост и когда последний раз был на связи;
 *   <li>{@code request.json} — просьба другого компьютера передать сайт ему;
 *   <li>{@code snapshots/} — снимки базы с ключами (последние три);
 *   <li>{@code files/}, {@code avatars/} — зеркало файлов: файлы не меняются, поэтому копируются по
 *       одному разу, а не в каждый снимок.
 * </ul>
 *
 * Облачный диск доставляет файлы с задержкой и в любом порядке, поэтому всё пишется во временный
 * файл и переименовывается, а снимок готов, только когда на месте все его файлы.
 */
public final class SiteFolder {

  public static final String ROOT = "groupbase-site";
  static final int FORMAT = 1;

  /** s-поколение-время-компьютер.zip: по имени снимки упорядочены так же, как по времени. */
  static final Pattern SNAPSHOT = Pattern.compile("s-(\\d{6})-(\\d{13})-([a-z0-9]{1,12})\\.zip");

  static final String RUNNING = "running";
  static final String STOPPED = "stopped";
  static final String RESTARTING = "restarting";
  static final String DETACHED = "detached";

  static final JsonMapper JSON =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .build();

  public record Site(int format, String id, String name, long createdAt) {}

  public record Snap(String name, long createdAt, long size, String computer) {}

  /**
   * Кто хост. {@code state}: running — работает, stopped — закрыт (сайт можно брать), restarting —
   * перезапускается или обновляется (скоро вернётся), detached — перенос между компьютерами
   * выключен. {@code to}, {@code toName} — кому передан сайт по просьбе.
   */
  public record Host(
      int format,
      String computerId,
      String computer,
      long epoch,
      String state,
      long heartbeat,
      long startedAt,
      Snap snapshot,
      String to,
      String toName,
      String version) {}

  public record Request(String computerId, String computer, long at) {}

  /** Файл зеркала: путь от каталога данных ({@code files/…}, {@code avatars/…}) и размер. */
  public record Entry(String p, long s) {}

  private final Path dir;

  public SiteFolder(Path dir) {
    this.dir = dir.toAbsolutePath().normalize();
  }

  public static SiteFolder in(Path cloudRoot, String siteId) {
    return new SiteFolder(cloudRoot.resolve(ROOT).resolve(siteId));
  }

  public Path dir() {
    return dir;
  }

  Path snapshots() {
    return dir.resolve("snapshots");
  }

  public Path snapshot(String name) {
    if (!SNAPSHOT.matcher(name).matches()) {
      throw new IllegalArgumentException("Неверное имя снимка");
    }
    return snapshots().resolve(name);
  }

  // ---------- записи ----------

  public Optional<Site> site() throws IOException {
    return read("site.json", Site.class);
  }

  void writeSite(Site s) throws IOException {
    write("site.json", s);
  }

  /** Пусто — хоста ещё не было; IOException — файл не читается (облако ещё докачивает его). */
  public Optional<Host> host() throws IOException {
    return read("host.json", Host.class);
  }

  void writeHost(Host h) throws IOException {
    write("host.json", h);
  }

  Optional<Request> request() {
    try {
      return read("request.json", Request.class);
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  void writeRequest(Request r) throws IOException {
    write("request.json", r);
  }

  void deleteRequest() {
    try {
      Files.deleteIfExists(dir.resolve("request.json"));
    } catch (IOException e) {
      // Облако держит файл — удалим в следующий раз, просьба всё равно устареет.
    }
  }

  private <T> Optional<T> read(String name, Class<T> type) throws IOException {
    Path f = dir.resolve(name);
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(f);
    } catch (NoSuchFileException e) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(JSON.readValue(bytes, type));
    } catch (RuntimeException e) {
      throw new IOException("Файл " + name + " ещё не докачан облаком", e);
    }
  }

  private void write(String name, Object value) throws IOException {
    Files.createDirectories(dir);
    Path tmp = dir.resolve("." + name + ".tmp");
    Files.write(tmp, JSON.writeValueAsBytes(value));
    move(tmp, dir.resolve(name));
  }

  /** Переименование поверх: на Windows облачный клиент может на миг держать файл — пробуем ещё. */
  static void move(Path from, Path to) throws IOException {
    IOException last = null;
    for (int i = 0; i < 5; i++) {
      try {
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return;
      } catch (FileAlreadyExistsException | java.nio.file.AccessDeniedException e) {
        last = e;
      } catch (java.nio.file.AtomicMoveNotSupportedException e) {
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        return;
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    Files.deleteIfExists(from);
    throw last == null ? new IOException("Не удалось записать " + to.getFileName()) : last;
  }

  // ---------- снимки ----------

  /** Имена снимков по порядку — от старых к новым. */
  public List<String> snapshotNames() {
    List<String> out = new ArrayList<>();
    if (!Files.isDirectory(snapshots())) {
      return out;
    }
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(snapshots(), "s-*.zip")) {
      for (Path p : ds) {
        String n = p.getFileName().toString();
        if (SNAPSHOT.matcher(n).matches()) {
          out.add(n);
        }
      }
    } catch (IOException e) {
      // Папку не видно — снимков как будто нет.
    }
    out.sort(null);
    return out;
  }

  static String snapshotName(long epoch, long createdAt, String computerId) {
    String who = computerId.replaceAll("[^a-z0-9]", "");
    who = who.length() > 8 ? who.substring(0, 8) : who.isEmpty() ? "pc" : who;
    return "s-%06d-%013d-%s.zip".formatted(epoch, createdAt, who);
  }

  public static long epochOf(String name) {
    Matcher m = SNAPSHOT.matcher(name);
    return m.matches() ? Long.parseLong(m.group(1)) : -1;
  }

  public static long timeOf(String name) {
    Matcher m = SNAPSHOT.matcher(name);
    return m.matches() ? Long.parseLong(m.group(2)) : -1;
  }

  /** Список файлов снимка (mirror.json в архиве). */
  static List<Entry> entries(Path zip) throws IOException {
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
      for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
        if (e.getName().equals(SiteSnapshot.MIRROR)) {
          try {
            return JSON.readValue(in.readAllBytes(), new TypeReference<List<Entry>>() {});
          } catch (RuntimeException ex) {
            throw new IOException("Снимок повреждён", ex);
          }
        }
      }
    }
    throw new IOException("Снимок ещё не докачан облаком");
  }

  /** Каких файлов снимка ещё нет в зеркале (облако не докачало). */
  List<Entry> missing(Collection<Entry> entries) {
    List<Entry> out = new ArrayList<>();
    for (Entry e : entries) {
      Path f = mirrorPath(e.p());
      try {
        if (!Files.isRegularFile(f) || Files.size(f) != e.s()) {
          out.add(e);
        }
      } catch (IOException ex) {
        out.add(e);
      }
    }
    return out;
  }

  /** Путь файла в зеркале; выйти за пределы папки нельзя. */
  Path mirrorPath(String rel) {
    if (!rel.startsWith("files/") && !rel.startsWith("avatars/")) {
      throw new IllegalArgumentException("Неверный путь в снимке: " + rel);
    }
    Path p = dir.resolve(rel).normalize();
    if (!p.startsWith(dir)) {
      throw new IllegalArgumentException("Неверный путь в снимке: " + rel);
    }
    return p;
  }

  /**
   * Копирует в зеркало новые файлы каталога данных (files/ и avatars/) и возвращает список всех.
   * Файлы с тем же именем и размером уже там — их не трогаем.
   */
  List<Entry> mirror(Path dataDir) throws IOException {
    List<Entry> out = new ArrayList<>();
    for (String top : List.of("files", "avatars")) {
      Path root = dataDir.resolve(top);
      if (!Files.isDirectory(root)) {
        continue;
      }
      List<Path> all;
      try (Stream<Path> walk = Files.walk(root)) {
        all = walk.filter(Files::isRegularFile).sorted().toList();
      }
      for (Path f : all) {
        String rel = top + "/" + root.relativize(f).toString().replace('\\', '/');
        if (rel.endsWith(".part") || rel.endsWith(".tmp")) {
          continue;
        }
        long size;
        try {
          size = Files.size(f);
        } catch (NoSuchFileException e) {
          continue; // файл только что удалили
        }
        Path target = mirrorPath(rel);
        if (!Files.isRegularFile(target) || Files.size(target) != size) {
          Files.createDirectories(target.getParent());
          Path part = target.resolveSibling(target.getFileName() + ".part");
          try {
            Files.copy(f, part, StandardCopyOption.REPLACE_EXISTING);
          } catch (NoSuchFileException e) {
            continue;
          }
          move(part, target);
        }
        out.add(new Entry(rel, size));
      }
    }
    return out;
  }

  /**
   * Оставляет {@code keep} новых снимков. Из зеркала убирает файлы, которых нет ни в одном из них и
   * которые не менялись {@code quietMs} — свежий файл может быть из снимка другого компьютера,
   * который облако ещё не доставило.
   */
  void rotate(int keep, long now, long quietMs) throws IOException {
    List<String> names = snapshotNames();
    for (int i = 0; i < names.size() - keep; i++) {
      Files.deleteIfExists(snapshots().resolve(names.get(i)));
    }
    names = snapshotNames();
    Set<String> used = new HashSet<>();
    for (String n : names) {
      try {
        for (Entry e : entries(snapshots().resolve(n))) {
          used.add(e.p());
        }
      } catch (IOException e) {
        return; // снимок ещё докачивается — не знаем, что в нём, ничего не удаляем
      }
    }
    for (String top : List.of("files", "avatars")) {
      Path root = dir.resolve(top);
      if (!Files.isDirectory(root)) {
        continue;
      }
      List<Path> all;
      try (Stream<Path> walk = Files.walk(root)) {
        all = walk.filter(Files::isRegularFile).toList();
      }
      for (Path f : all) {
        String rel = top + "/" + root.relativize(f).toString().replace('\\', '/');
        try {
          if (!used.contains(rel)
              && now - Files.getLastModifiedTime(f).toMillis() > quietMs
              && !rel.endsWith(".tmp")) {
            Files.deleteIfExists(f);
          }
        } catch (IOException e) {
          // Удалим в другой раз.
        }
      }
    }
  }

  /** Архив снимка целиком читается (проверка CRC каждой записи). */
  static void verify(Path zip) throws IOException {
    try (InputStream raw = Files.newInputStream(zip);
        ZipInputStream in = new ZipInputStream(raw)) {
      byte[] buf = new byte[64 * 1024];
      int n = 0;
      for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
        while (in.read(buf) >= 0) {
          // только читаем
        }
        n++;
      }
      if (n == 0) {
        throw new IOException("Снимок пуст");
      }
    } catch (java.util.zip.ZipException e) {
      throw new IOException("Снимок ещё не докачан облаком", e);
    }
  }
}
