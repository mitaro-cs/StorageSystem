package app.groupbase.hosts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.groupbase.hosts.SiteFolder.Entry;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Общая папка: имена снимков, зеркало файлов, записи о хосте, уборка. */
class SiteFolderTest {

  static final long DAY = 24 * 3600_000L;

  @Test
  void snapshotNamesSortLikeTime() {
    String a = SiteFolder.snapshotName(2, 1_790_000_000_000L, "3f2c-9a0e-77");
    String b = SiteFolder.snapshotName(2, 1_790_000_060_000L, "zz");
    String c = SiteFolder.snapshotName(10, 1_700_000_000_000L, "zz");
    assertThat(a).isEqualTo("s-000002-1790000000000-3f2c9a0e.zip");
    assertThat(List.of(c, b, a).stream().sorted().toList()).containsExactly(a, b, c);
    assertThat(SiteFolder.epochOf(c)).isEqualTo(10);
    assertThat(SiteFolder.timeOf(b)).isEqualTo(1_790_000_060_000L);
    assertThat(SiteFolder.epochOf("groupbase-2026-09-01-120000.zip")).isEqualTo(-1);
  }

  @Test
  void mirrorCopiesOnlyNewFiles(@TempDir Path data, @TempDir Path cloud) throws IOException {
    Files.createDirectories(data.resolve("files/ab"));
    Files.writeString(data.resolve("files/ab/abc-1"), "лекция");
    Files.createDirectories(data.resolve("avatars"));
    Files.writeString(data.resolve("avatars/f00-64.webp"), "аватар");
    Files.writeString(data.resolve("files/ab/abc-2.part"), "недокачан");
    SiteFolder f = new SiteFolder(cloud.resolve("site"));

    List<Entry> first = f.mirror(data);
    assertThat(first).extracting(Entry::p).containsExactly("files/ab/abc-1", "avatars/f00-64.webp");
    assertThat(Files.readString(cloud.resolve("site/files/ab/abc-1"))).isEqualTo("лекция");
    assertThat(f.missing(first)).isEmpty();

    // Уже скопированный файл не переписывается — облако не качает его заново.
    FileTime before = Files.getLastModifiedTime(cloud.resolve("site/files/ab/abc-1"));
    Files.setLastModifiedTime(cloud.resolve("site/files/ab/abc-1"), FileTime.fromMillis(1000));
    f.mirror(data);
    assertThat(Files.getLastModifiedTime(cloud.resolve("site/files/ab/abc-1")).toMillis())
        .isEqualTo(1000);
    assertThat(before.toMillis()).isNotEqualTo(1000);

    // Нет файла или другой размер — ещё не докачан.
    assertThat(f.missing(List.of(new Entry("files/ab/abc-1", 3), new Entry("files/zz/x", 1))))
        .hasSize(2);
    assertThatThrownBy(() -> f.mirrorPath("files/../../etc/passwd"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> f.mirrorPath("secrets/app.key"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void hostRecordRoundTripAndHalfSyncedFile(@TempDir Path cloud) throws IOException {
    SiteFolder f = new SiteFolder(cloud);
    assertThat(f.host()).isEmpty();
    f.writeHost(
        new SiteFolder.Host(1, "id", "ПК дома", 3, "running", 5, 1, null, null, null, "0.4.9"));
    assertThat(f.host().orElseThrow().computer()).isEqualTo("ПК дома");
    // Облако ещё качает файл — читается обрывок.
    Files.writeString(cloud.resolve("host.json"), "{\"computerId\":\"id\",\"epo");
    assertThatThrownBy(f::host).isInstanceOf(IOException.class);
    // Поля из более новой версии не мешают.
    Files.writeString(
        cloud.resolve("host.json"), "{\"computerId\":\"id\",\"epoch\":4,\"future\":true}");
    assertThat(f.host().orElseThrow().epoch()).isEqualTo(4);
  }

  @Test
  void rotateKeepsNewestAndDropsOnlyOldUnusedFiles(@TempDir Path cloud) throws IOException {
    SiteFolder f = new SiteFolder(cloud);
    long now = 10 * DAY;
    for (int i = 1; i <= 4; i++) {
      snapshot(
          f, SiteFolder.snapshotName(1, now - (5 - i) * 60_000L, "pc"), List.of("files/a/" + i));
    }
    for (String n : List.of("files/a/1", "files/a/2", "files/a/3", "files/a/4", "files/a/fresh")) {
      Path p = cloud.resolve(n);
      Files.createDirectories(p.getParent());
      Files.writeString(p, "x");
      Files.setLastModifiedTime(
          p, FileTime.fromMillis(n.endsWith("fresh") ? now - 60_000 : now - 5 * DAY));
    }
    f.rotate(3, now, 2 * DAY);
    assertThat(f.snapshotNames()).hasSize(3);
    // Файл из удалённого снимка давно никому не нужен — удалён; свежий «ничей» — возможно, из
    // снимка другого компьютера, который ещё едет, — остаётся.
    assertThat(cloud.resolve("files/a/1")).doesNotExist();
    assertThat(cloud.resolve("files/a/2")).exists();
    assertThat(cloud.resolve("files/a/fresh")).exists();
  }

  static void snapshot(SiteFolder f, String name, List<String> files) throws IOException {
    Path zip = f.snapshot(name);
    Files.createDirectories(zip.getParent());
    try (OutputStream out = Files.newOutputStream(zip);
        ZipOutputStream z = new ZipOutputStream(out)) {
      z.putNextEntry(new ZipEntry(SiteSnapshot.MIRROR));
      z.write(SiteFolder.JSON.writeValueAsBytes(files.stream().map(p -> new Entry(p, 1)).toList()));
      z.closeEntry();
    }
  }
}
