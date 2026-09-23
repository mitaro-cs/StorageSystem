package app.groupbase.access;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;

/**
 * Дочерний процесс клиента туннеля: читает его вывод построчно (без цветовых кодов) и сообщает о
 * завершении. PID записывается в файл, чтобы после аварийного выхода сервера не остался «висящий»
 * туннель, который занимает адрес.
 */
final class TunnelProcess {

  private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[A-Za-z]");

  private final Process process;
  private final Path pidFile;
  private volatile boolean stopping;

  private TunnelProcess(Process process, Path pidFile) {
    this.process = process;
    this.pidFile = pidFile;
  }

  static TunnelProcess start(
      List<String> cmd,
      Map<String, String> env,
      Path pidFile,
      Consumer<String> onLine,
      IntConsumer onExit)
      throws IOException {
    killStale(pidFile, cmd.getFirst());
    ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
    pb.environment().putAll(env);
    pb.redirectInput(ProcessBuilder.Redirect.PIPE);
    Process p = pb.start();
    p.getOutputStream().close();
    Files.createDirectories(pidFile.getParent());
    Files.writeString(pidFile, String.valueOf(p.pid()));
    TunnelProcess tp = new TunnelProcess(p, pidFile);
    Thread.ofVirtual()
        .name("tunnel-out")
        .start(
            () -> {
              try (BufferedReader r =
                  new BufferedReader(
                      new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                  onLine.accept(ANSI.matcher(line).replaceAll("").strip());
                }
              } catch (IOException e) {
                // Поток закрылся вместе с процессом.
              }
              int code;
              try {
                code = p.waitFor();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                code = -1;
              }
              try {
                Files.deleteIfExists(pidFile);
              } catch (IOException e) {
                // не критично
              }
              if (!tp.stopping) {
                onExit.accept(code);
              }
            });
    return tp;
  }

  boolean alive() {
    return process.isAlive();
  }

  void stop() {
    stopping = true;
    process.destroy();
    try {
      if (!process.waitFor(3, TimeUnit.SECONDS)) {
        process.destroyForcibly();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
    }
    try {
      Files.deleteIfExists(pidFile);
    } catch (IOException e) {
      // не критично
    }
  }

  /** Процесс с прошлого запуска, если он ещё жив и это действительно наш клиент туннеля. */
  static void killStale(Path pidFile, String executable) {
    try {
      if (!Files.isRegularFile(pidFile)) {
        return;
      }
      long pid = Long.parseLong(Files.readString(pidFile).strip());
      ProcessHandle.of(pid)
          .filter(ProcessHandle::isAlive)
          .filter(
              h ->
                  h.info()
                      .command()
                      .map(c -> Path.of(c).getFileName().equals(Path.of(executable).getFileName()))
                      .orElse(false))
          .ifPresent(ProcessHandle::destroy);
      Files.deleteIfExists(pidFile);
    } catch (IOException | NumberFormatException e) {
      // Нет файла или мусор в нём — нечего останавливать.
    }
  }
}
