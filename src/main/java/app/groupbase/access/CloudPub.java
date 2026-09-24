package app.groupbase.access;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Клиент CloudPub (cloudpub.ru, открытый клиент — github.com/ermak-dev/cloudpub): российский
 * туннель, связь с сервером — WebSocket по TLS на порту 443. Поэтому работает и с VPN, и без, и в
 * сетях, где открыт только обычный HTTPS (Wi‑Fi вуза, общежития).
 *
 * <p>Адрес постоянный: сервис один раз регистрируется на сервере CloudPub ({@code clo register}), а
 * при каждом запуске поднимается командой {@code clo run} с тем же адресом. Настройки клиента (в
 * том числе его токен) — в {@code tools/cloudpub/client.toml} каталога данных.
 */
final class CloudPub {

  static final String VERSION = "3.5.1056";
  private static final Map<String, String> SHA256 =
      Map.of(
          "darwin-arm64", "1fbf7655750762268c5b2fc23f498c6275ddc56e5bd8131716b1b5ba9d285e35",
          "darwin-amd64", "9ad1e1e7608ad0dd2f5ab823e7a9dfd18291616cbd5f756ca548a309da26408c",
          "windows-amd64", "db1238b3e3f01686d5856b7b6ad8095fe18803ba70e13fe7912faa3e954fe904",
          "linux-amd64", "23a8a9e9c2faa2d2fc5d65fe1487cbf3e9aecf8874dcda4f13755214adb78e25",
          "linux-arm64", "b8645665e37512bfb0573da8be0fb5835d943180c18c5b6675598aa61fa18120");
  private static final Map<String, String> ASSET =
      Map.of(
          "darwin-arm64", "macos-aarch64.tar.gz",
          "darwin-amd64", "macos-x86_64.tar.gz",
          "windows-amd64", "windows-x86_64.zip",
          "linux-amd64", "linux-x86_64.tar.gz",
          "linux-arm64", "linux-aarch64.tar.gz");

  /** «… -> https://имя.cloudpub.ru:443/» — адрес сервиса в строке клиента. */
  static final Pattern URL = Pattern.compile("->\\s*(https://[a-z0-9.-]+\\.cloudpub\\.ru)");

  private static final Pattern TOKEN_LINE = Pattern.compile("(?m)^\\s*token\\s*=\\s*\"[^\"]+\"");
  static final Pattern AUTH_ERROR =
      Pattern.compile("(?i)(токен|авториз|token|unauthori[sz]ed|login|парол|password)");

  private final Path dir;
  private final Path override;

  CloudPub(Path toolsDir, Path override) {
    this.dir = toolsDir.resolve("cloudpub");
    this.override = override;
  }

  Path conf() {
    return dir.resolve("client.toml");
  }

  Path pidFile() {
    return dir.resolve("run.pid");
  }

  /** Токен сохранён в настройках клиента — вход выполнен. */
  boolean loggedIn() {
    try {
      return Files.isRegularFile(conf()) && TOKEN_LINE.matcher(Files.readString(conf())).find();
    } catch (IOException e) {
      return false;
    }
  }

  Path binary() throws IOException {
    if (override != null) {
      return override;
    }
    Platform p = Platform.current();
    String key = p.os() + "-" + p.arch();
    String sha = SHA256.get(key);
    if (sha == null) {
      throw new IOException("для этой системы нет сборки клиента CloudPub");
    }
    String asset = "clo-" + VERSION + "-stable-" + ASSET.get(key);
    return new Tool(
            "клиент CloudPub", URI.create("https://cloudpub.ru/download/stable/" + asset), sha)
        .ensureFromArchive(dir.resolve(p.exe("clo")), p.exe("clo"));
  }

  /** Итог короткой команды клиента: код выхода и весь вывод. */
  record Result(int code, String output) {
    boolean ok() {
      return code == 0;
    }

    /** Последняя непустая строка — обычно в ней и объяснение ошибки. */
    String lastLine() {
      String[] lines = output.strip().split("\\R");
      return lines.length == 0 ? "" : lines[lines.length - 1].strip();
    }
  }

  /**
   * Вход почтой и паролем. Пароль — через переменную окружения, в командной строке его не видно.
   */
  Result login(String email, String password) throws IOException {
    return run(List.of("login", email), Map.of("CLO_PASSWORD", password), 60);
  }

  Result setToken(String token) throws IOException {
    return run(List.of("set", "token", token), Map.of(), 30);
  }

  Result logout() throws IOException {
    return run(List.of("logout"), Map.of(), 30);
  }

  /** Регистрирует сервис на сервере и возвращает его постоянный адрес. */
  String register(int port) throws IOException {
    Result r =
        run(List.of("register", "-n", "groupbase", "http", "127.0.0.1:" + port), Map.of(), 90);
    String url = findUrl(r.output());
    if (!r.ok() || url == null) {
      throw new IOException(r.lastLine().isEmpty() ? "CloudPub не выдал адрес" : r.lastLine());
    }
    return url;
  }

  /**
   * Адрес уже зарегистрированного сервиса для этого порта ({@code clo ls}), если он есть: так при
   * повторном входе и перезапусках не плодятся новые адреса.
   */
  String registered(int port) throws IOException {
    Result r = run(List.of("ls"), Map.of(), 60);
    if (!r.ok()) {
      return null;
    }
    Pattern ours =
        Pattern.compile(
            "http://127\\.0\\.0\\.1:" + port + "\\S*\\s*->\\s*(https://[a-z0-9.-]+\\.cloudpub\\.ru)");
    Matcher m = ours.matcher(r.output());
    return m.find() ? m.group(1) : null;
  }

  /** Долгоживущая команда: поднимает все зарегистрированные сервисы. */
  List<String> runCommand(Path bin) {
    return List.of(bin.toString(), "--conf", conf().toString(), "run");
  }

  static String findUrl(String output) {
    Matcher m = URL.matcher(output);
    String url = null;
    while (m.find()) {
      url = m.group(1);
    }
    return url;
  }

  private Result run(List<String> args, Map<String, String> env, int timeoutSec)
      throws IOException {
    Path bin = binary();
    Files.createDirectories(dir);
    List<String> cmd = new ArrayList<>(List.of(bin.toString(), "--conf", conf().toString()));
    cmd.addAll(args);
    ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
    pb.environment().putAll(env);
    Process p = pb.start();
    p.getOutputStream().close();
    StringBuilder out = new StringBuilder();
    Thread reader =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (BufferedReader r =
                      new BufferedReader(
                          new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line; (line = r.readLine()) != null; ) {
                      synchronized (out) {
                        out.append(line.replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "")).append('\n');
                      }
                    }
                  } catch (IOException e) {
                    // процесс закрыл вывод
                  }
                });
    try {
      if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new IOException("CloudPub не ответил за " + timeoutSec + " секунд");
      }
      reader.join(2000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      p.destroyForcibly();
      throw new IOException("Прервано", e);
    }
    restrictConf();
    synchronized (out) {
      return new Result(p.exitValue(), out.toString());
    }
  }

  /** В настройках клиента лежит его токен — только для владельца. */
  private void restrictConf() {
    try {
      if (Files.isRegularFile(conf())
          && java.nio.file.FileSystems.getDefault()
              .supportedFileAttributeViews()
              .contains("posix")) {
        Files.setPosixFilePermissions(
            conf(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
      }
    } catch (IOException e) {
      // не критично
    }
  }
}
