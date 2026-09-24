package app.groupbase.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Команды обслуживания на настоящем каталоге данных: seed, user, backup, restore, doctor, init. */
class CommandsIT {

  private record Run(int code, String out, String err) {}

  private static Run run(String... args) {
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    CommandLine cl = Main.commandLine();
    cl.setOut(new PrintWriter(out));
    cl.setErr(new PrintWriter(err));
    int code = cl.execute(args);
    return new Run(code, out.toString(), err.toString());
  }

  private static String sql(Path data, String query) throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("groupbase.db"));
        Statement st = c.createStatement()) {
      if (!query.stripLeading().toUpperCase(java.util.Locale.ROOT).startsWith("SELECT")) {
        st.executeUpdate(query);
        return null;
      }
      try (ResultSet rs = st.executeQuery(query)) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  @Test
  void seedFillsAnEmptySiteOnlyOnce(@TempDir Path data) throws Exception {
    Run seed = run("seed", "-d", data.toString());
    assertThat(seed.code()).as(seed.err()).isZero();
    assertThat(seed.out()).contains("ivanova");
    assertThat(sql(data, "SELECT count(*) FROM users")).isEqualTo("9");
    assertThat(sql(data, "SELECT count(*) FROM homework WHERE kind = 'exam'")).isEqualTo("3");
    assertThat(sql(data, "SELECT session_from IS NOT NULL FROM study_groups")).isEqualTo("1");

    Run again = run("seed", "-d", data.toString());
    assertThat(again.code()).isEqualTo(1);
    assertThat(again.err()).contains("уже настроен");

    Run list = run("user", "list", "-d", data.toString());
    assertThat(list.code()).isZero();
    assertThat(list.out()).contains("ivanova", "Иванова Анна Сергеевна", "admin");
  }

  @Test
  void resetTwoFactorAndPasswordWhenAdminLostAccess(@TempDir Path data) throws Exception {
    assertThat(run("seed", "-d", data.toString()).code()).isZero();
    sql(data, "UPDATE users SET totp_enabled = 1, totp_secret = x'00' WHERE username = 'ivanova'");
    sql(
        data,
        "INSERT INTO sessions (token_hash, user_id, created_at, last_seen_at, expires_at)"
            + " SELECT x'01', id, 0, 0, 9999999999999 FROM users WHERE username = 'ivanova'");

    Run reset = run("user", "reset-2fa", "ivanova", "-d", data.toString());
    assertThat(reset.code()).as(reset.err()).isZero();
    assertThat(sql(data, "SELECT totp_enabled FROM users WHERE username = 'ivanova'"))
        .isEqualTo("0");
    assertThat(sql(data, "SELECT count(*) FROM sessions")).isEqualTo("0");
    assertThat(sql(data, "SELECT action FROM audit_log ORDER BY id DESC LIMIT 1"))
        .isEqualTo("user.reset_2fa");

    Run link = run("user", "reset-password", "IVANOVA", "-d", data.toString());
    assertThat(link.code()).isZero();
    assertThat(link.out()).containsPattern("http://localhost:\\d+/activate/[\\w-]{20,}");

    Run unknown = run("user", "reset-2fa", "nobody", "-d", data.toString());
    assertThat(unknown.code()).isEqualTo(1);
    assertThat(unknown.err()).contains("nobody");
  }

  @Test
  void backupRestoreRoundTripAndDoctor(@TempDir Path tmp) throws Exception {
    Path data = tmp.resolve("data");
    assertThat(run("seed", "-d", data.toString()).code()).isZero();
    Path zip = tmp.resolve("copy.zip");
    Run backup = run("backup", "-d", data.toString(), "-o", zip.toString());
    assertThat(backup.code()).as(backup.err()).isZero();
    assertThat(zip).isRegularFile();

    Path other = tmp.resolve("restored");
    Run noConfirm = run("restore", "-d", other.toString(), zip.toString());
    assertThat(noConfirm.code()).isEqualTo(1);
    assertThat(other.resolve("groupbase.db")).doesNotExist();

    Run restore = run("restore", "-d", other.toString(), "--yes", zip.toString());
    assertThat(restore.code()).as(restore.err()).isZero();
    assertThat(restore.out()).contains("пользователей 9");
    assertThat(sql(other, "SELECT count(*) FROM homework")).isEqualTo("11");

    Run doctor = run("doctor", "-d", other.toString());
    assertThat(doctor.code()).as(doctor.out()).isZero();
    assertThat(doctor.out()).contains("✓ База цела", "✓ Ключи шифрования на месте", "все на месте");

    // Пропал файл — doctor это видит и возвращает 1.
    try (var walk = Files.walk(other.resolve("files"))) {
      for (Path p : walk.filter(Files::isRegularFile).toList()) {
        Files.delete(p);
      }
    }
    Run broken = run("doctor", "-d", other.toString());
    assertThat(broken.code()).isEqualTo(1);
    assertThat(broken.out()).contains("✗ Пропали с диска файлов: 1 из 1");
  }

  @Test
  void doctorOnMissingDataFailsWithoutCreatingAnything(@TempDir Path tmp) {
    Path none = tmp.resolve("none");
    Run r = run("doctor", "-d", none.toString());
    assertThat(r.code()).isEqualTo(1);
    assertThat(none).doesNotExist();
  }

  @Test
  void initCreatesGroupAndHeadman(@TempDir Path data) throws Exception {
    InputStream in = System.in;
    try {
      System.setIn(new ByteArrayInputStream("init-password-1\n".getBytes(StandardCharsets.UTF_8)));
      Run r =
          run(
              "init",
              "-d",
              data.toString(),
              "--group",
              "БИК2402",
              "--name",
              "Петрова Мария Ивановна",
              "--password-stdin");
      assertThat(r.code()).as(r.err()).isZero();
      assertThat(r.out()).contains("petrova.mariya");
    } finally {
      System.setIn(in);
    }
    assertThat(sql(data, "SELECT name FROM study_groups")).isEqualTo("БИК2402");
    assertThat(sql(data, "SELECT role FROM memberships")).isEqualTo("headman");
    assertThat(run("init", "-d", data.toString(), "--group", "X", "--name", "Y Z").code())
        .isEqualTo(1);
  }

  @Test
  void helpListsMaintenanceCommands() {
    Run r = run("--help");
    for (String c : List.of("init", "user", "backup", "restore", "doctor", "seed")) {
      assertThat(r.out()).contains(c);
    }
  }
}
