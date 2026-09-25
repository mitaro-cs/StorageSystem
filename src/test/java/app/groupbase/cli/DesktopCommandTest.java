package app.groupbase.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.BindException;
import org.junit.jupiter.api.Test;

/** Коды ошибок запуска сервера в приложении хоста: по ним оболочка показывает причину и совет. */
class DesktopCommandTest {

  private static DesktopCommand.Problem of(Throwable e) {
    return DesktopCommand.problem(e);
  }

  @Test
  void knownCausesGetTheirCodes() {
    assertThat(
            of(new IllegalStateException("x", new BindException("Address already in use"))).code())
        .isEqualTo("GB-201");
    assertThat(of(new IllegalStateException("Сервер с каталогом данных /d уже запущен")).code())
        .isEqualTo("GB-202");
    assertThat(of(new UncheckedIOException(new IOException("No space left on device"))).code())
        .isEqualTo("GB-207");
    assertThat(
            of(new RuntimeException(
                    "boom",
                    new RuntimeException("[SQLITE_CORRUPT] database disk image is malformed")))
                .code())
        .isEqualTo("GB-206");
    assertThat(of(new IOException(AppContext.RESTORE_FAILED + ": Это не бэкап groupbase")).code())
        .isEqualTo("GB-208");
  }

  @Test
  void unknownCauseKeepsTheDeepestMessage() {
    var p = of(new RuntimeException("обёртка", new IllegalArgumentException("настоящая причина")));
    assertThat(p.code()).isEqualTo("GB-200");
    assertThat(p.message()).isEqualTo("настоящая причина");
    assertThat(of(new RuntimeException()).message()).isNotBlank();
    // «malformed» в чужом сообщении — не повреждённая база.
    assertThat(of(new IllegalArgumentException("malformed URL")).code()).isEqualTo("GB-200");
  }
}
