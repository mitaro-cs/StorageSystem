package app.groupbase.hosts;

import app.groupbase.backup.BackupService;
import app.groupbase.hosts.SiteFolder.Entry;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Снимок сайта для другого компьютера: база ({@code VACUUM INTO}, без остановки сервера), ключи,
 * вход в CloudPub (с ним и адрес сайта остаётся прежним) и список файлов. Сами файлы — в зеркале
 * общей папки. Восстановление — при запуске, до того как сервер откроет базу.
 */
final class SiteSnapshot {

  static final String MANIFEST = "manifest.json";
  static final String MIRROR = "mirror.json";
  static final String DB = "groupbase.db";
  static final String CLOUDPUB = "cloudpub/client.toml";
  static final String KIND = "site-snapshot";

  /** Сколько прежних «before-switch-…» держать в каталоге данных. */
  static final int KEEP_ASIDE = 3;

  /** Снимок есть, но облако ещё не докачало его или часть файлов. */
  static final class Incomplete extends IOException {
    final int have;
    final int total;

    Incomplete(String message, int have, int total) {
      super(message);
      this.have = have;
      this.total = total;
    }
  }

  record Meta(long epoch, String computerId, String computer, String version, int port) {}

  private SiteSnapshot() {}

  static Path cloudpubConf(Path dataDir) {
    return dataDir.resolve("tools").resolve("cloudpub").resolve("client.toml");
  }

  /** Пишет снимок в общую папку (сначала — новые файлы в зеркало). */
  static SiteFolder.Snap write(JdbcClient db, Path dataDir, SiteFolder folder, Meta meta, long now)
      throws IOException {
    List<Entry> entries = folder.mirror(dataDir);
    String name = SiteFolder.snapshotName(meta.epoch(), now, meta.computerId());
    Path target = folder.snapshot(name);
    Files.createDirectories(target.getParent());
    Path part = target.resolveSibling(name + ".part");
    Path snap = dataDir.resolve(".site-" + now + "-" + Thread.currentThread().threadId() + ".db");
    Files.deleteIfExists(snap);
    try {
      db.sql("VACUUM INTO ?").param(snap.toAbsolutePath().toString()).update();
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("format", 1);
      m.put("kind", KIND);
      m.put("version", meta.version());
      m.put("createdAt", now);
      m.put(
          "schema",
          db.sql(
                  "SELECT max(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success"
                      + " = 1")
              .query(Integer.class)
              .single());
      m.put("epoch", meta.epoch());
      m.put("computerId", meta.computerId());
      m.put("computer", meta.computer());
      m.put("port", meta.port());
      try (OutputStream out = Files.newOutputStream(part);
          ZipOutputStream zip = new ZipOutputStream(out)) {
        bytes(zip, MANIFEST, SiteFolder.JSON.writeValueAsBytes(m));
        file(zip, DB, snap);
        Path secrets = dataDir.resolve("secrets");
        if (Files.isDirectory(secrets)) {
          try (Stream<Path> walk = Files.walk(secrets)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
              file(zip, "secrets/" + secrets.relativize(p).toString().replace('\\', '/'), p);
            }
          }
        }
        Path cp = cloudpubConf(dataDir);
        if (Files.isRegularFile(cp)) {
          file(zip, CLOUDPUB, cp);
        }
        bytes(zip, MIRROR, SiteFolder.JSON.writeValueAsBytes(entries));
      }
      SiteFolder.move(part, target);
    } finally {
      Files.deleteIfExists(snap);
      Files.deleteIfExists(part);
    }
    return new SiteFolder.Snap(name, now, Files.size(target), meta.computer());
  }

  private static void bytes(ZipOutputStream zip, String name, byte[] data) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(data);
    zip.closeEntry();
  }

  private static void file(ZipOutputStream zip, String name, Path p) throws IOException {
    ZipEntry e = new ZipEntry(name);
    e.setTime(Files.getLastModifiedTime(p).toMillis());
    zip.putNextEntry(e);
    Files.copy(p, zip);
    zip.closeEntry();
  }

  /**
   * Готов ли снимок: архив целиком на месте и в зеркале есть все его файлы.
   *
   * @throws Incomplete облако ещё докачивает — со счётом готовых файлов
   */
  static List<Entry> ready(SiteFolder folder, String name) throws IOException {
    Path zip = folder.snapshot(name);
    if (!Files.isRegularFile(zip)) {
      throw new Incomplete("Облако ещё не докачало снимок данных", 0, 1);
    }
    try {
      SiteFolder.verify(zip);
    } catch (IOException e) {
      throw new Incomplete("Облако ещё не докачало снимок данных", 0, 1);
    }
    List<Entry> entries = SiteFolder.entries(zip);
    List<Entry> missing = folder.missing(entries);
    if (!missing.isEmpty()) {
      throw new Incomplete(
          "Облако докачивает файлы сайта", entries.size() - missing.size(), entries.size());
    }
    return entries;
  }

  /** Порт сайта на прежнем компьютере — чтобы адрес в CloudPub вёл туда же. */
  static int port(SiteFolder folder, String name) {
    try {
      return BackupService.readManifest(folder.snapshot(name)).path("port").asInt(0);
    } catch (IOException | RuntimeException e) {
      return 0;
    }
  }

  /**
   * Берёт снимок из общей папки в каталог данных. Сначала всё проверяется и распаковывается рядом:
   * если снимок не докачан или повреждён, прежние данные остаются как были. Потом прежние база,
   * ключи и лишние файлы переносятся в {@code before-switch-…} — ничего не удаляется.
   */
  static void restore(Path dataDir, SiteFolder folder, String name, Consumer<String> say)
      throws IOException {
    List<Entry> entries = ready(folder, name);
    Path zip = folder.snapshot(name);
    JsonNode manifest = BackupService.readManifest(zip);
    BackupService.check(manifest);
    Path data = dataDir.toAbsolutePath().normalize();
    long now = System.currentTimeMillis();
    Path stage = data.resolve("restore").resolve("site-" + now);
    Path aside = data.resolve("before-switch-" + now);
    try {
      extract(zip, stage);
      if (!Files.isRegularFile(stage.resolve(DB))) {
        throw new IOException("В снимке нет базы");
      }
      // Файлы: недостающие — из зеркала (новые имена прежней базе не мешают).
      Set<String> wanted = new HashSet<>();
      int copied = 0;
      for (Entry e : entries) {
        wanted.add(e.p());
        Path local = inside(data, e.p());
        if (Files.isRegularFile(local) && Files.size(local) == e.s()) {
          continue;
        }
        if (Files.exists(local)) {
          moveAside(data, local, aside);
        }
        Files.createDirectories(local.getParent());
        Path part = local.resolveSibling(local.getFileName() + ".part");
        Files.copy(folder.mirrorPath(e.p()), part, StandardCopyOption.REPLACE_EXISTING);
        Files.move(part, local, StandardCopyOption.REPLACE_EXISTING);
        copied++;
      }
      // База и ключи — целиком.
      for (String n : List.of(DB, DB + "-wal", DB + "-shm")) {
        Path p = data.resolve(n);
        if (Files.exists(p)) {
          moveAside(data, p, aside);
        }
      }
      Files.move(stage.resolve(DB), data.resolve(DB));
      if (Files.isDirectory(stage.resolve("secrets"))) {
        if (Files.exists(data.resolve("secrets"))) {
          moveAside(data, data.resolve("secrets"), aside);
        }
        Files.move(stage.resolve("secrets"), data.resolve("secrets"));
      }
      Path cp = stage.resolve(CLOUDPUB);
      if (Files.isRegularFile(cp)) {
        Path mine = cloudpubConf(data);
        if (Files.exists(mine)) {
          Files.createDirectories(aside);
          Files.move(mine, aside.resolve("cloudpub-client.toml"));
        }
        Files.createDirectories(mine.getParent());
        Files.move(cp, mine);
      }
      // Файлы, которых в снимке нет (удалены там), — в сторону.
      int extra = 0;
      for (String top : List.of("files", "avatars")) {
        Path root = data.resolve(top);
        if (!Files.isDirectory(root)) {
          continue;
        }
        List<Path> all;
        try (Stream<Path> walk = Files.walk(root)) {
          all = walk.filter(Files::isRegularFile).toList();
        }
        for (Path f : all) {
          String rel = top + "/" + root.relativize(f).toString().replace('\\', '/');
          if (!wanted.contains(rel)) {
            moveAside(data, f, aside);
            extra++;
          }
        }
      }
      say.accept(
          "Данные взяты: скопировано файлов — "
              + copied
              + (extra > 0 ? ", отложено лишних — " + extra : ""));
    } finally {
      deleteTree(stage);
    }
    prune(data);
  }

  private static void extract(Path zip, Path stage) throws IOException {
    Files.createDirectories(stage);
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
      for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
        String n = e.getName();
        if (e.isDirectory() || !(n.equals(DB) || n.startsWith("secrets/") || n.equals(CLOUDPUB))) {
          continue;
        }
        Path target = inside(stage, n);
        Files.createDirectories(target.getParent());
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  private static Path inside(Path root, String rel) throws IOException {
    Path p = root.resolve(rel).normalize();
    if (!p.startsWith(root) || p.equals(root)) {
      throw new IOException("Подозрительный путь в снимке: " + rel);
    }
    return p;
  }

  private static void moveAside(Path data, Path p, Path aside) throws IOException {
    Path target = aside.resolve(data.relativize(p).toString());
    Files.createDirectories(target.getParent());
    Files.move(p, target, StandardCopyOption.REPLACE_EXISTING);
  }

  /** Прежние данные после переносов копятся — держим последние {@link #KEEP_ASIDE}. */
  static void prune(Path data) {
    List<Path> all = new ArrayList<>();
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(data, "before-switch-*")) {
      ds.forEach(all::add);
    } catch (IOException e) {
      return;
    }
    all.sort(null);
    for (int i = 0; i < all.size() - KEEP_ASIDE; i++) {
      deleteTree(all.get(i));
    }
  }

  static void deleteTree(Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    } catch (IOException e) {
      // Не страшно: уберём в следующий раз.
    }
  }
}
