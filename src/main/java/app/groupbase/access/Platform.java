package app.groupbase.access;

import java.util.Locale;

/** Операционная система и процессор — для выбора сборки клиента туннеля. */
public record Platform(String os, String arch) {

  public static Platform current() {
    String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String os =
        name.contains("mac") || name.contains("darwin")
            ? "darwin"
            : name.contains("win") ? "windows" : "linux";
    String a = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    String arch = a.contains("aarch64") || a.contains("arm64") ? "arm64" : "amd64";
    return new Platform(os, arch);
  }

  public boolean windows() {
    return os.equals("windows");
  }

  public String exe(String name) {
    return windows() ? name + ".exe" : name;
  }
}
