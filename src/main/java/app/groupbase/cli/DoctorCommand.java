package app.groupbase.cli;

import app.groupbase.backup.BackupService;
import app.groupbase.config.Secrets;
import app.groupbase.store.DataDirLock;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Spec;

/**
 * Проверка сервера без изменений: база открывается только для чтения, ничего не создаётся. Код
 * выхода 1 — есть проблема, которую надо чинить.
 */
@Command(
    name = "doctor",
    description = "Проверить данные: база, ключи, файлы, место на диске, копии. Ничего не меняет.",
    mixinStandardHelpOptions = true)
public class DoctorCommand implements Callable<Integer> {

  enum Level {
    OK("✓"),
    WARN("!"),
    FAIL("✗");

    final String mark;

    Level(String mark) {
      this.mark = mark;
    }
  }

  record Check(Level level, String text) {}

  static final long GB = 1024L * 1024 * 1024;

  @Mixin Target target;
  @Spec picocli.CommandLine.Model.CommandSpec spec;

  @Override
  public Integer call() throws Exception {
    PrintWriter out = spec.commandLine().getOut();
    Path data = target.dataDir();
    out.println("groupbase " + Main.version() + ", Java " + Runtime.version().feature());
    out.println("Данные: " + data);
    String baseUrl = target.environment().getProperty("groupbase.base-url", "");
    List<Check> checks = run(data, baseUrl, System.currentTimeMillis());
    for (Check c : checks) {
      out.println("  " + c.level().mark + " " + c.text());
    }
    long fails = checks.stream().filter(c -> c.level() == Level.FAIL).count();
    long warns = checks.stream().filter(c -> c.level() == Level.WARN).count();
    out.println(
        fails > 0
            ? "Есть проблемы: " + fails + "."
            : warns > 0 ? "Работает, но есть на что посмотреть: " + warns + "." : "Всё в порядке.");
    return fails > 0 ? 1 : 0;
  }

  /**
   * @param baseUrl адрес из groupbase.toml; пусто — берётся адрес доступа, выбранный в приложении
   */
  static List<Check> run(Path data, String baseUrl, long now) {
    List<Check> out = new ArrayList<>();
    if (!Files.isDirectory(data)) {
      out.add(new Check(Level.FAIL, "Каталога данных нет — укажите его через --data"));
      return out;
    }
    out.add(
        Files.isWritable(data)
            ? new Check(Level.OK, "Каталог данных доступен на запись")
            : new Check(Level.FAIL, "Нет прав на запись в каталог данных"));
    out.add(disk(data));
    out.add(
        DataDirLock.held(data)
            ? new Check(Level.OK, "Сервер запущен")
            : new Check(Level.WARN, "Сервер сейчас не запущен"));
    Path dbFile = data.resolve("groupbase.db");
    if (!Files.isRegularFile(dbFile)) {
      out.add(new Check(Level.FAIL, "Базы groupbase.db нет — сайт ещё не запускался здесь"));
      return out;
    }
    out.add(keys(data));
    try (Connection c =
            DriverManager.getConnection(
                "jdbc:sqlite:file:" + dbFile.toAbsolutePath() + "?mode=ro");
        Statement st = c.createStatement()) {
      st.execute("PRAGMA busy_timeout = 5000");
      String quick = one(st, "PRAGMA quick_check");
      out.add(
          "ok".equals(quick)
              ? new Check(Level.OK, "База цела")
              : new Check(Level.FAIL, "База повреждена: " + quick + " — восстановите из копии"));
      out.add(schema(st));
      long admins =
          Long.parseLong(
              one(
                  st,
                  "SELECT count(*) FROM users WHERE instance_role = 'admin' AND status = 'active'"));
      long users = Long.parseLong(one(st, "SELECT count(*) FROM users WHERE status = 'active'"));
      out.add(
          admins > 0
              ? new Check(Level.OK, "Пользователей: " + users + ", администраторов: " + admins)
              : users == 0
                  ? new Check(Level.WARN, "Сайт ещё не настроен: groupbase init или ссылка из лога")
                  : new Check(Level.FAIL, "Нет ни одного администратора"));
      out.add(files(st, data));
      out.add(backups(st, now));
      String url = baseUrl.isBlank() ? setting(st, "access.url") : baseUrl;
      out.add(
          url == null || url.isBlank()
              ? new Check(Level.WARN, "Адрес для группы не задан — участники не смогут зайти")
              : new Check(Level.OK, "Адрес для группы: " + url));
    } catch (SQLException e) {
      out.add(new Check(Level.FAIL, "Базу не открыть: " + e.getMessage()));
    }
    return out;
  }

  private static Check disk(Path data) {
    try {
      long free = Files.getFileStore(data).getUsableSpace();
      String gb =
          String.format(java.util.Locale.ROOT, "%.1f ГБ", free / (double) GB).replace('.', ',');
      if (free < GB / 5) {
        return new Check(Level.FAIL, "Свободно всего " + gb + " — освободите место на диске");
      }
      return free < 2 * GB
          ? new Check(Level.WARN, "Свободно " + gb + " — места мало")
          : new Check(Level.OK, "Свободно на диске: " + gb);
    } catch (IOException e) {
      return new Check(Level.WARN, "Не удалось узнать свободное место");
    }
  }

  private static Check keys(Path data) {
    Path dir = data.resolve("secrets");
    boolean env = System.getenv("GROUPBASE_FILES_KEY") != null;
    if (!env && !Files.isRegularFile(dir.resolve(Secrets.FILES_KEY))) {
      return new Check(
          Level.FAIL,
          "Нет ключа шифрования файлов (secrets/"
              + Secrets.FILES_KEY
              + ") — файлы не открыть, восстановите его из копии");
    }
    if (!Files.isRegularFile(dir.resolve(Secrets.APP_KEY))
        && System.getenv("GROUPBASE_APP_KEY") == null) {
      return new Check(Level.FAIL, "Нет ключа приложения (secrets/" + Secrets.APP_KEY + ")");
    }
    return new Check(Level.OK, "Ключи шифрования на месте");
  }

  private static Check schema(Statement st) throws SQLException {
    int version =
        Integer.parseInt(
            one(
                st,
                "SELECT coalesce(max(CAST(version AS INTEGER)), 0) FROM flyway_schema_history"
                    + " WHERE success = 1"));
    int latest;
    try {
      latest = BackupService.latestSchema();
    } catch (IOException e) {
      return new Check(Level.WARN, "Версия базы: " + version);
    }
    if (version > latest) {
      return new Check(
          Level.FAIL,
          "База от более новой версии groupbase (" + version + ") — обновите программу");
    }
    return version < latest
        ? new Check(
            Level.OK, "Версия базы " + version + ", обновится до " + latest + " при запуске")
        : new Check(Level.OK, "Версия базы: " + version);
  }

  /** Файлы, записанные в базе, но пропавшие с диска. */
  private static Check files(Statement st, Path data) throws SQLException {
    int total = 0;
    int missing = 0;
    try (ResultSet rs = st.executeQuery("SELECT uuid FROM files")) {
      while (rs.next()) {
        total++;
        String uuid = rs.getString(1);
        if (uuid == null || uuid.length() < 2) {
          continue;
        }
        if (!Files.isRegularFile(
            data.resolve("files").resolve(uuid.substring(0, 2)).resolve(uuid))) {
          missing++;
        }
      }
    }
    return missing == 0
        ? new Check(Level.OK, "Файлов: " + total + ", все на месте")
        : new Check(Level.FAIL, "Пропали с диска файлов: " + missing + " из " + total);
  }

  private static Check backups(Statement st, long now) throws SQLException {
    String ok = setting(st, "backup.last_ok_at");
    String error = setting(st, "backup.last_error");
    if (error != null && !error.isBlank()) {
      return new Check(Level.WARN, "Последняя копия не удалась: " + error);
    }
    if (ok == null || ok.isBlank()) {
      return new Check(Level.WARN, "Резервных копий ещё не было — groupbase backup");
    }
    long days = Duration.ofMillis(now - Long.parseLong(ok)).toDays();
    return days > 3
        ? new Check(Level.WARN, "Последняя копия — " + days + " дн. назад")
        : new Check(
            Level.OK, days == 0 ? "Копия сделана сегодня" : "Копия — " + days + " дн. назад");
  }

  private static String setting(Statement st, String key) throws SQLException {
    try (ResultSet rs =
        st.executeQuery(
            "SELECT value FROM instance_settings WHERE key = '" + key.replace("'", "''") + "'")) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  private static String one(Statement st, String sql) throws SQLException {
    try (ResultSet rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }
}
