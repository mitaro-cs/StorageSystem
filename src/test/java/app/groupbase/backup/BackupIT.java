package app.groupbase.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.groupbase.ApiClient;
import app.groupbase.IntegrationTest;
import app.groupbase.store.SettingsStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/** Резервные копии из админки: создать, скачать, восстановить при следующем запуске. */
class BackupIT extends IntegrationTest {

  @Test
  void createDownloadAndStageRestore() throws IOException {
    ApiClient a = admin();
    // Вход в CloudPub едет вместе с копией: на новом компьютере адрес сайта останется прежним.
    Path cloudpub = props.toolsDir().resolve("cloudpub/client.toml");
    Files.createDirectories(cloudpub.getParent());
    Files.writeString(cloudpub, "token = \"test\"\n");
    var created = a.post("/api/admin/backups", Map.of());
    assertThat(created.status()).as(created.body()).isEqualTo(200);
    String name = created.json().get("name").asString();
    assertThat(name).matches("groupbase-\\d{4}-\\d{2}-\\d{2}-\\d{6}\\.zip");

    var list = a.get("/api/admin/backups").json();
    assertThat(list.get("items").get(0).get("name").asString()).isEqualTo(name);
    assertThat(list.get("status").get("lastOkAt").asLong()).isPositive();

    byte[] zip = a.download("/api/admin/backups/" + name).body();
    Set<String> entries = new HashSet<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
        entries.add(e.getName());
      }
    }
    assertThat(entries)
        .contains("manifest.json", "groupbase.db", "secrets/app.key", "tools/cloudpub/client.toml");

    // На обычном сервере восстановление ставится в очередь до перезапуска.
    var staged = a.post("/api/admin/backups/" + name + "/restore", Map.of());
    assertThat(staged.status()).as(staged.body()).isEqualTo(200);
    assertThat(staged.json().get("status").asString()).isEqualTo("staged");
    Path pending = PendingRestore.file(props.dataDir());
    assertThat(pending).exists();

    // «Перезапуск»: распаковка в другой каталог, как это сделает сервер при старте.
    Path other = Files.createTempDirectory("groupbase-restore");
    Files.writeString(other.resolve("groupbase.db"), "старая база");
    Files.copy(pending, other.resolve("pending.zip"));
    Path aside = BackupService.restore(other.resolve("pending.zip"), other, m -> {});
    assertThat(other.resolve("secrets/app.key")).exists();
    assertThat(other.resolve("tools/cloudpub/client.toml")).hasContent("token = \"test\"");
    assertThat(Files.size(other.resolve("groupbase.db"))).isGreaterThan(100);
    assertThat(Files.readString(aside.resolve("groupbase.db"))).isEqualTo("старая база");
    Files.delete(pending);
  }

  @Test
  void rejectsForeignArchivesAndStudents() throws IOException {
    ApiClient a = admin();
    var bad = a.putRaw("/api/admin/backups/restore", "not a zip".getBytes());
    assertThat(bad.status()).isEqualTo(400);
    assertThat(bad.json().get("message").asString()).startsWith("Это не архив ZIP");

    // Частая путаница: выгрузка группы (для чтения) вместо копии — объясняем, что нужно.
    java.io.ByteArrayOutputStream export = new java.io.ByteArrayOutputStream();
    try (ZipOutputStream z = new ZipOutputStream(export)) {
      z.putNextEntry(new ZipEntry("Предметы/Физика/О предмете.md"));
      z.write("# Физика".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      z.closeEntry();
      z.putNextEntry(new ZipEntry("data.json"));
      z.write("{}".getBytes());
      z.closeEntry();
    }
    var wrong = a.putRaw("/api/admin/backups/restore", export.toByteArray());
    assertThat(wrong.status()).isEqualTo(400);
    assertThat(wrong.json().get("message").asString())
        .contains("выгрузка группы")
        .contains("Резервные копии");

    // Копия от более новой версии: схема выше, чем знает эта программа.
    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
    try (ZipOutputStream z = new ZipOutputStream(buf)) {
      z.putNextEntry(new ZipEntry("manifest.json"));
      z.write("{\"format\":1,\"schema\":999}".getBytes());
      z.closeEntry();
    }
    var newer = a.putRaw("/api/admin/backups/restore", buf.toByteArray());
    assertThat(newer.status()).isEqualTo(400);
    assertThat(newer.json().get("message").asString()).contains("более новой версией");

    long g = newGroup("Копии");
    ApiClient s = newUser(g, "student").api();
    assertThat(s.get("/api/admin/backups").status()).isEqualTo(403);
    assertThat(s.post("/api/admin/backups", Map.of()).status()).isEqualTo(403);
    // Отдаются только файлы копий по шаблону имени; обход каталога отклоняется ещё Tomcat.
    assertThat(a.get("/api/admin/backups/..%2Fgroupbase.db").status()).isIn(400, 404);
    assertThat(a.get("/api/admin/backups/groupbase.db").status()).isEqualTo(404);
  }

  @Test
  void zipSlipIsRefused() throws IOException {
    Path dir = Files.createTempDirectory("groupbase-slip");
    Path zip = dir.resolve("evil.zip");
    try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
      z.putNextEntry(new ZipEntry("manifest.json"));
      z.write("{\"format\":1,\"schema\":1}".getBytes());
      z.closeEntry();
      z.putNextEntry(new ZipEntry("../outside.txt"));
      z.write("x".getBytes());
      z.closeEntry();
    }
    Path data = Files.createDirectories(dir.resolve("data"));
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> BackupService.restore(zip, data, m -> {}))
        .hasMessageContaining("Подозрительный путь");
    assertThat(dir.resolve("outside.txt")).doesNotExist();
  }

  @Autowired BackupService backups;
  @Autowired SettingsStore settings;

  @Test
  void everyCopyIsVerifiedAndMirroredToTheSecondDisk(@TempDir Path usb) throws IOException {
    ApiClient a = admin();
    settings.set(BackupService.SETTING_MIRROR, usb.toString());
    var created = a.post("/api/admin/backups", Map.of());
    assertThat(created.status()).as(created.body()).isEqualTo(200);
    String name = created.json().get("name").asString();

    // Проверена (архив читается, база цела) и лежит на втором диске – байт в байт.
    var view = a.get("/api/admin/backups").json();
    assertThat(view.get("status").get("verifiedAt").asLong()).isPositive();
    assertThat(view.get("status").get("mirrorOkAt").asLong()).isPositive();
    assertThat(view.get("mirror").get("chosen").get("path").asString()).isEqualTo(usb.toString());
    assertThat(view.get("mirror").get("present").asBoolean()).isTrue();
    Path copy = usb.resolve(BackupService.CLOUD_SUBDIR).resolve(name);
    assertThat(BackupService.sha256(copy))
        .isEqualTo(BackupService.sha256(backups.dir().resolve(name)));
    assertThat(BackupService.verify(copy, props.dataDir()).entries()).isGreaterThan(2);

    // Диск вынули: копии делаются, о дубле – понятная причина; вставили – дубль ложится сам.
    Path gone = usb.resolveSibling(usb.getFileName() + "-gone");
    Files.move(usb, gone);
    backups.syncMirror();
    assertThat(backups.status().mirrorError()).contains("не подключён");
    Files.move(gone, usb);
    Files.delete(copy);
    backups.syncMirror();
    assertThat(copy).exists();
    assertThat(backups.status().mirrorError()).isNull();

    // Выбрать можно только найденный диск.
    var bad = a.put("/api/admin/backups/mirror", Map.of("path", "/etc"));
    assertThat(bad.status()).isEqualTo(400);
    settings.set(BackupService.SETTING_MIRROR, "");
  }

  @Test
  void brokenCopyIsCaughtByVerification(@TempDir Path dir) throws IOException {
    admin();
    BackupService.Info info = backups.create();
    Path good = backups.dir().resolve(info.name());
    byte[] bytes = Files.readAllBytes(good);
    // Порча посередине архива: CRC части не сойдётся.
    for (int i = bytes.length / 2; i < bytes.length / 2 + 64; i++) {
      bytes[i] ^= (byte) 0x5a;
    }
    Path broken = dir.resolve(info.name());
    Files.write(broken, bytes);
    assertThatThrownBy(() -> BackupService.verify(broken, dir)).isInstanceOf(IOException.class);
    assertThat(BackupService.verify(good, dir).bytes()).isPositive();
    try (var files = Files.list(dir)) {
      assertThat(files.filter(p -> p.getFileName().toString().startsWith(".verify-"))).isEmpty();
    }
  }

  @Test
  void setupRestoreNeedsTheSetupCode() {
    admin();
    var r = client().putRaw("/api/setup/restore?code=wrong", new byte[] {1, 2, 3});
    assertThat(r.status()).isEqualTo(403);
  }
}
