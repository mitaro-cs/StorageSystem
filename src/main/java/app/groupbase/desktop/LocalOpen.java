package app.groupbase.desktop;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Открыть папку в Finder или Проводнике на компьютере хоста. */
public final class LocalOpen {

  private LocalOpen() {}

  public static void folder(Path dir) throws IOException {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    List<String> cmd;
    if (os.contains("mac")) {
      cmd = List.of("open", dir.toString());
    } else if (os.contains("win")) {
      cmd = List.of("explorer.exe", dir.toString());
    } else {
      cmd = List.of("xdg-open", dir.toString());
    }
    new ProcessBuilder(cmd).redirectErrorStream(true).start();
  }
}
