package app.groupbase.web.api;

import app.groupbase.auth.Actor;
import app.groupbase.auth.Permission;
import app.groupbase.backup.BackupService;
import app.groupbase.cli.Main;
import app.groupbase.config.GroupbaseProperties;
import app.groupbase.status.UpdateCheck;
import app.groupbase.web.Require;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** «Состояние сервера» для администратора: версия, место на диске, резервные копии, обновления. */
@RestController
class StatusController {

  record Sizes(long database, long files, long free) {}

  /**
   * @param dataDir путь к данным — только в окне приложения на компьютере хоста
   */
  record View(
      String version,
      boolean desktop,
      long startedAt,
      String dataDir,
      Sizes sizes,
      BackupService.Status backup,
      UpdateCheck.Update update,
      boolean canUpdate) {}

  private final GroupbaseProperties props;
  private final BackupService backups;
  private final UpdateCheck updates;
  private final app.groupbase.desktop.DesktopBridge bridge;

  StatusController(
      GroupbaseProperties props,
      BackupService backups,
      UpdateCheck updates,
      app.groupbase.desktop.DesktopBridge bridge) {
    this.props = props;
    this.backups = backups;
    this.updates = updates;
    this.bridge = bridge;
  }

  @Require(Permission.MANAGE_INSTANCE)
  @GetMapping("/api/admin/status")
  View status(Actor actor) throws IOException {
    Path data = props.dataDir();
    long db = size(props.databaseFile()) + size(data.resolve("groupbase.db-wal"));
    long files = tree(props.filesDir()) + tree(data.resolve("avatars"));
    long free = Files.exists(data) ? Files.getFileStore(data).getUsableSpace() : 0;
    // В окне хоста новая версия ставится одной кнопкой — чья бы проверка её ни нашла.
    boolean host = bridge.enabled() && actor.local();
    if (host && bridge.availableUpdate() == null) {
      bridge.requestCheck();
    }
    UpdateCheck.Update update = update();
    return new View(
        Main.version(),
        props.desktop().enabled(),
        ManagementFactory.getRuntimeMXBean().getStartTime(),
        actor.local() ? data.toAbsolutePath().toString() : null,
        new Sizes(db, files, free),
        backups.status(),
        update,
        host && update != null);
  }

  /** Новая версия: в приложении хоста её находит оболочка, на своём сервере — запрос к GitHub. */
  private UpdateCheck.Update update() {
    String v = bridge.availableUpdate();
    if (v != null) {
      return new UpdateCheck.Update(
          v, "https://github.com/mitaro-cs/StorageSystem/releases/tag/v" + v);
    }
    return updates.available();
  }

  private static long size(Path p) throws IOException {
    return Files.isRegularFile(p) ? Files.size(p) : 0;
  }

  private static long tree(Path root) throws IOException {
    if (!Files.isDirectory(root)) {
      return 0;
    }
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(Files::isRegularFile)
          .mapToLong(
              p -> {
                try {
                  return Files.size(p);
                } catch (IOException e) {
                  return 0;
                }
              })
          .sum();
    }
  }
}
