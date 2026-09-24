package app.groupbase.cli;

import app.groupbase.backup.BackupService;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Резервная копия из командной строки; сервер можно не останавливать. */
@Command(
    name = "backup",
    description =
        "Сделать резервную копию: база, файлы и ключи шифрования в одном ZIP."
            + " Сервер можно не останавливать.",
    mixinStandardHelpOptions = true)
public class BackupCommand implements Callable<Integer> {

  @Mixin Target target;
  @Spec picocli.CommandLine.Model.CommandSpec spec;

  @Option(
      names = {"-o", "--out"},
      paramLabel = "ПУТЬ",
      description =
          "Куда сохранить: файл .zip или папка. По умолчанию — в папку копий из настроек"
              + " (старые сверх лимита удаляются).")
  Path out;

  @Override
  public Integer call() throws Exception {
    PrintWriter say = spec.commandLine().getOut();
    Path data = target.dataDir();
    if (!Files.isRegularFile(data.resolve("groupbase.db"))) {
      spec.commandLine()
          .getErr()
          .println("В " + data + " нет данных groupbase — нечего копировать");
      return 1;
    }
    try (var ctx = target.open()) {
      BackupService backups = ctx.getBean(BackupService.class);
      Path file;
      if (out == null) {
        file = backups.file(backups.create().name());
      } else {
        file = Files.isDirectory(out) ? out.resolve(defaultName()) : out;
        Path part = file.resolveSibling(file.getFileName() + ".part");
        try (OutputStream os = Files.newOutputStream(part)) {
          backups.write(os);
        } catch (Exception e) {
          Files.deleteIfExists(part);
          throw e;
        }
        Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
      }
      say.printf(
          "Копия готова: %s (%s)%n", file.toAbsolutePath().normalize(), size(Files.size(file)));
      say.println("В ней ключи шифрования — храните её так же бережно, как сам компьютер.");
      return 0;
    }
  }

  static String defaultName() {
    return "groupbase-"
        + DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss").format(LocalDateTime.now())
        + ".zip";
  }

  static String size(long bytes) {
    if (bytes < 1024 * 1024) {
      return Math.max(1, bytes / 1024) + " КБ";
    }
    return String.format(java.util.Locale.ROOT, "%.1f МБ", bytes / 1024.0 / 1024).replace('.', ',');
  }
}
