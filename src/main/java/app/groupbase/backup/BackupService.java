package app.groupbase.backup;

import app.groupbase.cli.Main;
import app.groupbase.config.GroupbaseProperties;
import app.groupbase.store.SettingsStore;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Бэкап — один ZIP: согласованный снимок базы ({@code VACUUM INTO}, без остановки сервера),
 * зашифрованные файлы, аватары и ключи (без ключей файлы не расшифровать). Восстановление
 * возвращает всё это в каталог данных при остановленном сервере.
 */
@Service
public class BackupService {

  public record Info(String name, long size, long createdAt) {}

  /** Итог последнего автоматического бэкапа. */
  public record Status(Long lastOkAt, String lastError, Long lastErrorAt) {}

  /** Выбранная папка облачного диска (корень); копии кладутся в её подпапку. */
  static final String SETTING_DIR = "backup.dir";

  static final String SETTING_OK = "backup.last_ok_at";
  static final String SETTING_ERROR = "backup.last_error";
  static final String SETTING_ERROR_AT = "backup.last_error_at";
  public static final String CLOUD_SUBDIR = "groupbase-backups";

  static final String DB = "groupbase.db";
  static final String CLOUDPUB = "tools/cloudpub/client.toml";
  static final String MANIFEST = "manifest.json";
  static final int FORMAT = 1;
  private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");
  private static final JsonMapper JSON =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  private final JdbcClient db;
  private final GroupbaseProperties props;
  private final SettingsStore settings;
  private final Clock clock;

  public BackupService(
      JdbcClient db, GroupbaseProperties props, SettingsStore settings, Clock clock) {
    this.db = db;
    this.props = props;
    this.settings = settings;
    this.clock = clock;
  }

  /** Куда складываются копии: выбранный облачный диск или каталог из настроек. */
  public Path dir() {
    return cloudRoot().map(r -> r.resolve(CLOUD_SUBDIR)).orElse(props.backupsDir());
  }

  /** Корень выбранного облачного диска, если он выбран и сейчас на месте. */
  public Optional<Path> cloudRoot() {
    return settings
        .get(SETTING_DIR)
        .filter(s -> !s.isBlank())
        .map(Path::of)
        .filter(Files::isDirectory);
  }

  /** Выбор папки — только из найденных облачных дисков или «по умолчанию» (null). */
  public void chooseCloud(Path root) {
    if (root != null
        && CloudFolders.detect().stream().noneMatch(f -> f.path().equals(root.normalize()))) {
      throw new IllegalArgumentException("Такой папки облачного диска нет");
    }
    settings.set(SETTING_DIR, root == null ? "" : root.normalize().toString());
  }

  public Status status() {
    return new Status(
        settings.get(SETTING_OK).map(Long::parseLong).orElse(null),
        settings.get(SETTING_ERROR).filter(s -> !s.isBlank()).orElse(null),
        settings.get(SETTING_ERROR_AT).map(Long::parseLong).orElse(null));
  }

  /**
   * Пора ли делать автоматическую копию: раз в сутки после времени {@code at}, а если компьютер в
   * это время был выключен — как только сервер снова работает (копии старше 23 часов).
   */
  public boolean due(long now) {
    if (!props.backup().enabled()) {
      return false;
    }
    Long last = status().lastOkAt();
    if (last == null) {
      return true;
    }
    if (now - last >= java.time.Duration.ofHours(23).toMillis()) {
      return true;
    }
    java.time.ZonedDateTime local = Instant.ofEpochMilli(now).atZone(props.timezone());
    long todayAt =
        local
            .toLocalDate()
            .atTime(java.time.LocalTime.parse(props.backup().at()))
            .atZone(props.timezone())
            .toInstant()
            .toEpochMilli();
    return now >= todayAt && last < todayAt;
  }

  /** Автоматическая копия с записью итога (для панели «Состояние»). */
  public void scheduled() {
    try {
      create();
    } catch (IOException | RuntimeException e) {
      settings.set(SETTING_ERROR, e.getMessage() == null ? e.toString() : e.getMessage());
      settings.set(SETTING_ERROR_AT, String.valueOf(clock.millis()));
      throw e instanceof RuntimeException re
          ? re
          : new java.io.UncheckedIOException((IOException) e);
    }
  }

  /** Новый бэкап в каталог бэкапов; старые сверх лимита удаляются. */
  public synchronized Info create() throws IOException {
    Path dir = dir();
    Files.createDirectories(dir);
    long now = clock.millis();
    String name =
        "groupbase-" + NAME.format(Instant.ofEpochMilli(now).atZone(props.timezone())) + ".zip";
    Path part = dir.resolve(name + ".part");
    try (OutputStream out = Files.newOutputStream(part)) {
      write(out);
    } catch (IOException | RuntimeException e) {
      Files.deleteIfExists(part);
      throw e;
    }
    Path target = dir.resolve(name);
    Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    rotate();
    settings.set(SETTING_OK, String.valueOf(now));
    settings.set(SETTING_ERROR, "");
    return new Info(name, Files.size(target), now);
  }

  /**
   * Бэкап прямо в поток — для скачивания из админки. Ключи шифрования входят в копию: без них файлы
   * не расшифровать, поэтому копию нужно хранить так же бережно, как сам сервер. Вход в CloudPub —
   * тоже (на месте он встаёт при восстановлении, а бинарники туннелей не копируются).
   */
  public void write(OutputStream out) throws IOException {
    Path data = props.dataDir();
    Files.createDirectories(data);
    Path snap =
        data.resolve(".backup-" + clock.millis() + "-" + Thread.currentThread().threadId() + ".db");
    Files.deleteIfExists(snap);
    try {
      db.sql("VACUUM INTO ?").param(snap.toAbsolutePath().toString()).update();
      ZipOutputStream zip = new ZipOutputStream(out);
      zip.putNextEntry(new ZipEntry(MANIFEST));
      zip.write(JSON.writeValueAsBytes(manifest()));
      zip.closeEntry();
      add(zip, DB, snap);
      addTree(zip, "files", props.filesDir());
      addTree(zip, "avatars", data.resolve("avatars"));
      addTree(zip, "secrets", props.secretsDir());
      // Вход в CloudPub: с ним на новом компьютере у сайта останется прежний адрес.
      Path cloudpub = props.toolsDir().resolve("cloudpub").resolve("client.toml");
      if (Files.isRegularFile(cloudpub)) {
        add(zip, CLOUDPUB, cloudpub);
      }
      zip.finish();
      zip.flush();
    } finally {
      Files.deleteIfExists(snap);
    }
  }

  private Map<String, Object> manifest() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("format", FORMAT);
    m.put("version", Main.version());
    m.put("createdAt", clock.millis());
    m.put(
        "schema",
        db.sql("SELECT max(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success = 1")
            .query(Integer.class)
            .single());
    m.put("secrets", Files.isDirectory(props.secretsDir()));
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String t : List.of("users", "study_groups", "subjects", "homework", "posts", "files")) {
      counts.put(t, db.sql("SELECT count(*) FROM " + t).query(Integer.class).single());
    }
    m.put("counts", counts);
    return m;
  }

  private static void add(ZipOutputStream zip, String name, Path file) throws IOException {
    ZipEntry e = new ZipEntry(name);
    e.setTime(Files.getLastModifiedTime(file).toMillis());
    zip.putNextEntry(e);
    Files.copy(file, zip);
    zip.closeEntry();
  }

  private static void addTree(ZipOutputStream zip, String prefix, Path root) throws IOException {
    if (!Files.isDirectory(root)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
        String rel = root.relativize(p).toString().replace('\\', '/');
        if (rel.endsWith(".part")) {
          continue;
        }
        add(zip, prefix + "/" + rel, p);
      }
    }
  }

  public List<Info> list() throws IOException {
    List<Info> out = new ArrayList<>();
    Path dir = dir();
    if (!Files.isDirectory(dir)) {
      return out;
    }
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "groupbase-*.zip")) {
      for (Path p : ds) {
        out.add(
            new Info(
                p.getFileName().toString(),
                Files.size(p),
                Files.getLastModifiedTime(p).toMillis()));
      }
    }
    out.sort(Comparator.comparing(Info::name).reversed());
    return out;
  }

  public Path file(String name) {
    if (!name.matches("groupbase-\\d{4}-\\d{2}-\\d{2}-\\d{6}\\.zip")) {
      throw new IllegalArgumentException("Неверное имя бэкапа");
    }
    return dir().resolve(name);
  }

  /** Оставляет {@code keep} самых новых. */
  public void rotate() throws IOException {
    List<Info> all = list();
    for (int i = Math.max(1, props.backup().keep()); i < all.size(); i++) {
      Files.deleteIfExists(dir().resolve(all.get(i).name()));
    }
  }

  // ---------- восстановление (сервер остановлен) ----------

  /** Где взять правильный файл — для сообщений об ошибке. */
  static final String WHERE =
      "Нужен файл groupbase-ГГГГ-ММ-ДД-….zip: на прежнем компьютере — «Настройки → Сервер →"
          + " Резервные копии», кнопка скачивания у копии.";

  /**
   * manifest.json из архива. Если это не копия — объясняем, что именно выбрали: чаще всего это
   * выгрузка группы (она для чтения, данных сайта в ней нет) или вообще не архив.
   */
  public static JsonNode readManifest(Path zip) throws IOException {
    boolean any = false;
    boolean export = false;
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
      for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
        any = true;
        String n = e.getName();
        if (n.equals(MANIFEST)) {
          return JSON.readTree(in.readAllBytes());
        }
        if (n.equals("data.json")
            || n.startsWith("Предметы/")
            || n.startsWith("Новости/")
            || n.startsWith("Мои публикации/")) {
          export = true;
        }
      }
    } catch (java.util.zip.ZipException | IllegalArgumentException e) {
      throw new IOException("Это не архив ZIP. " + WHERE, e);
    }
    if (!any) {
      throw new IOException("Это не архив ZIP. " + WHERE);
    }
    if (export) {
      throw new IOException(
          "Это выгрузка группы (архив для чтения), а не резервная копия сайта. " + WHERE);
    }
    throw new IOException(
        "Это не резервная копия groupbase: в архиве нет " + MANIFEST + ". " + WHERE);
  }

  /** Снимок для других компьютеров хоста — не копия: его берут сами компьютеры из общей папки. */
  public static void requireBackup(JsonNode manifest) throws IOException {
    if ("site-snapshot".equals(manifest.path("kind").asString(""))) {
      throw new IOException(
          "Это снимок для работы на нескольких компьютерах — его берут сами компьютеры хоста. "
              + WHERE);
    }
  }

  /** Номер последней миграции, которую знает эта версия программы. */
  public static int latestSchema() throws IOException {
    return Migrations.latest();
  }

  /** Можно ли восстановить эту копию этой версией программы. */
  public static void check(JsonNode manifest) throws IOException {
    if (manifest.path("format").asInt() > FORMAT
        || manifest.path("schema").asInt() > Migrations.latest()) {
      throw new IOException(
          "Копия сделана более новой версией groupbase — сначала обновите приложение");
    }
  }

  /**
   * Распаковывает бэкап в каталог данных. Что было — переносится в {@code before-restore-…}, ничего
   * не удаляется. Пути внутри архива проверяются: выйти за пределы каталога данных нельзя.
   */
  public static Path restore(Path zip, Path dataDir, Consumer<String> say) throws IOException {
    check(readManifest(zip));
    Path data = dataDir.toAbsolutePath().normalize();
    Files.createDirectories(data);
    // Откладываем в сторону только то, что есть в архиве (и всегда базу с её журналом WAL).
    Set<String> present = new HashSet<>(List.of(DB, DB + "-wal", DB + "-shm"));
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
      for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
        int slash = e.getName().indexOf('/');
        if (slash > 0) {
          present.add(e.getName().substring(0, slash));
        }
      }
    }
    Path aside = data.resolve("before-restore-" + System.currentTimeMillis());
    for (String name : List.of(DB, DB + "-wal", DB + "-shm", "files", "avatars", "secrets")) {
      Path p = data.resolve(name);
      if (present.contains(name) && Files.exists(p)) {
        Files.createDirectories(aside);
        Files.move(p, aside.resolve(name));
        say.accept("Прежнее «" + name + "» перенесено в " + aside.getFileName());
      }
    }
    int n = 0;
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
      for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
        if (e.isDirectory() || e.getName().equals(MANIFEST)) {
          continue;
        }
        Path target = data.resolve(e.getName()).normalize();
        if (!target.startsWith(data) || target.equals(data)) {
          throw new IOException("Подозрительный путь в архиве: " + e.getName());
        }
        Files.createDirectories(target.getParent());
        // ZipInputStream отдаёт конец данных на границе записи — копируется ровно одна запись.
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        n++;
      }
    }
    say.accept("Восстановлено файлов: " + n);
    return aside;
  }
}
