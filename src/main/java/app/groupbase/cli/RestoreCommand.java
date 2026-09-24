package app.groupbase.cli;

import app.groupbase.backup.BackupService;
import app.groupbase.backup.PendingRestore;
import app.groupbase.store.DataDirLock;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import tools.jackson.databind.JsonNode;

/**
 * Восстановление из копии. Ничего не удаляется: прежние данные переезжают в {@code
 * before-restore-…}. Если сервер запущен, копия встанет при его следующем запуске.
 */
@Command(
    name = "restore",
    description =
        "Восстановить данные из резервной копии. Прежние данные не удаляются, а переносятся"
            + " в папку before-restore-… рядом.",
    mixinStandardHelpOptions = true)
public class RestoreCommand implements Callable<Integer> {

  @Mixin Target target;
  @Spec picocli.CommandLine.Model.CommandSpec spec;

  @Parameters(paramLabel = "КОПИЯ.zip")
  Path zip;

  @Option(
      names = {"-y", "--yes"},
      description = "Не спрашивать подтверждения.")
  boolean yes;

  @Override
  public Integer call() throws Exception {
    PrintWriter out = spec.commandLine().getOut();
    PrintWriter err = spec.commandLine().getErr();
    if (!Files.isRegularFile(zip)) {
      err.println("Файл не найден: " + zip);
      return 1;
    }
    JsonNode manifest;
    try {
      manifest = BackupService.readManifest(zip);
      BackupService.check(manifest);
    } catch (java.io.IOException e) {
      err.println(e.getMessage());
      return 1;
    }
    Path data = target.dataDir();
    out.printf(
        "Копия groupbase %s: пользователей %d, заданий %d, файлов %d.%n",
        manifest.path("version").asString("?"),
        manifest.path("counts").path("users").asInt(),
        manifest.path("counts").path("homework").asInt(),
        manifest.path("counts").path("files").asInt());
    out.println("Восстановить в " + data + "?");
    if (!yes && !confirm()) {
      err.println("Отменено. Чтобы не спрашивать, добавьте --yes.");
      return 1;
    }
    if (DataDirLock.held(data)) {
      PendingRestore.stage(zip, data);
      out.println("Сервер сейчас работает — копия будет восстановлена при его следующем запуске.");
      out.println("Перезапустите сервер или приложение хоста.");
      return 0;
    }
    Path aside = BackupService.restore(zip, data, out::println);
    out.println("Готово. Прежние данные — в " + aside);
    return 0;
  }

  private boolean confirm() {
    java.io.Console console = System.console();
    if (console == null) {
      return false;
    }
    String answer = console.readLine("Да/нет [д/Н]: ");
    return answer != null
        && answer.strip().toLowerCase(java.util.Locale.ROOT).matches("д|да|y|yes");
  }
}
