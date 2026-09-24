package app.groupbase.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class MainTest {

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

  @Test
  void helpShowsServeButHidesShellOnlyDesktop() {
    Run r = run("--help");
    assertThat(r.code()).isZero();
    assertThat(r.out()).contains("serve");
    // «desktop» запускает только оболочка приложения хоста — в справке людям он не нужен.
    assertThat(r.out()).doesNotContain("desktop");
    assertThat(Main.commandLine().getSubcommands()).containsKey("desktop");
  }

  @Test
  void versionIsPrintedWithoutStartingServer() {
    Run r = run("--version");
    assertThat(r.code()).isZero();
    assertThat(r.out()).startsWith("groupbase ");
    assertThat(Main.version()).isNotBlank();
  }

  @Test
  void unknownCommandFails() {
    Run r = run("launch-rockets");
    assertThat(r.code()).isNotZero();
    assertThat(r.err()).contains("launch-rockets");
  }

  @Test
  void desktopCommandDescribesItsOptions() {
    Run r = run("help", "desktop");
    assertThat(r.code()).isZero();
    assertThat(r.out()).contains("--data");
  }
}
