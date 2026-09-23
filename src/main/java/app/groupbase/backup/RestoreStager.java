package app.groupbase.backup;

import app.groupbase.config.GroupbaseProperties;
import app.groupbase.desktop.DesktopBridge;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * Восстановление из копии из интерфейса: архив проверяется и ставится в очередь, а применяется при
 * перезапуске. Приложение хоста перезапускает сервер само, на своём сервере это делает
 * администратор.
 */
@Service
public class RestoreStager {

  /** Итог: restarting — приложение перезапустит сервер; staged — перезапустите сервер сами. */
  public record Result(String status, String message) {}

  private final GroupbaseProperties props;
  private final BackupService backups;
  private final DesktopBridge bridge;

  public RestoreStager(GroupbaseProperties props, BackupService backups, DesktopBridge bridge) {
    this.props = props;
    this.backups = backups;
    this.bridge = bridge;
  }

  /** Копия из папки копий по имени. */
  public Result fromBackup(String name) throws IOException {
    Path zip = backups.file(name);
    if (!Files.isRegularFile(zip)) {
      throw new IOException("Копия не найдена");
    }
    PendingRestore.stage(zip, props.dataDir());
    return restart();
  }

  /** Загруженный архив (тело запроса). */
  public Result fromUpload(InputStream body) throws IOException {
    Path dir = props.dataDir().resolve(PendingRestore.DIR);
    Files.createDirectories(dir);
    Path tmp = Files.createTempFile(dir, "upload-", ".zip");
    try {
      Files.copy(body, tmp, StandardCopyOption.REPLACE_EXISTING);
      PendingRestore.stage(tmp, props.dataDir());
    } finally {
      Files.deleteIfExists(tmp);
    }
    return restart();
  }

  private Result restart() {
    if (bridge.enabled()) {
      // Сначала уйдёт ответ, потом оболочка перезапустит сервер.
      CompletableFuture.delayedExecutor(700, TimeUnit.MILLISECONDS).execute(bridge::requestRestart);
      return new Result("restarting", "Перезапускаем сервер и восстанавливаем данные…");
    }
    return new Result(
        "staged", "Копия проверена. Перезапустите сервер — данные восстановятся при запуске.");
  }
}
