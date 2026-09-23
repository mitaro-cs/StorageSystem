package app.groupbase.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

/**
 * Восстановление из копии, заказанное из интерфейса: архив кладётся в {@code restore/pending.zip},
 * а распаковывается при следующем старте — до того, как сервер откроет базу и ключи.
 */
public final class PendingRestore {

  static final String DIR = "restore";
  static final String PENDING = "pending.zip";

  private PendingRestore() {}

  public static Path file(Path dataDir) {
    return dataDir.resolve(DIR).resolve(PENDING);
  }

  /** Принимает архив: проверяет, что это бэкап groupbase, и ставит в очередь. */
  public static void stage(Path zip, Path dataDir) throws IOException {
    BackupService.check(BackupService.readManifest(zip));
    Path target = file(dataDir);
    Files.createDirectories(target.getParent());
    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
  }

  /**
   * @return true, если восстановление было и выполнено
   */
  public static boolean apply(Path dataDir, Consumer<String> say) throws IOException {
    Path pending = file(dataDir);
    if (!Files.isRegularFile(pending)) {
      return false;
    }
    say.accept("Восстанавливаем данные из резервной копии…");
    BackupService.restore(pending, dataDir, say);
    // Прежние данные остались в before-restore-…; сам архив больше не нужен.
    Files.delete(pending);
    return true;
  }
}
