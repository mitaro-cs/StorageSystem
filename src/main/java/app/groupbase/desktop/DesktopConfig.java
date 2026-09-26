package app.groupbase.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Сетевые настройки приложения хоста, нужные до старта сервера: порт и режим «локальная сеть».
 * Лежат в {@code desktop.properties} каталога данных. Порт постоянный: у окна хоста адрес
 * http://127.0.0.1:порт, и от него зависят сохранённые в окне данные.
 */
public final class DesktopConfig {

  public static final String FILE = "desktop.properties";
  public static final int DEFAULT_PORT = 17380;

  private final Path file;
  private int port;
  private boolean lan;

  private DesktopConfig(Path file, int port, boolean lan) {
    this.file = file;
    this.port = port;
    this.lan = lan;
  }

  public static DesktopConfig load(Path dataDir) throws IOException {
    Path f = dataDir.resolve(FILE);
    Properties p = new Properties();
    if (Files.isRegularFile(f)) {
      try (InputStream in = Files.newInputStream(f)) {
        p.load(in);
      }
    }
    int port;
    try {
      port = Integer.parseInt(p.getProperty("port", String.valueOf(DEFAULT_PORT)).trim());
    } catch (NumberFormatException e) {
      port = DEFAULT_PORT;
    }
    return new DesktopConfig(f, port, Boolean.parseBoolean(p.getProperty("lan", "false")));
  }

  public int port() {
    return port;
  }

  public boolean lan() {
    return lan;
  }

  public String bindAddress() {
    return lan ? "0.0.0.0" : "127.0.0.1";
  }

  public void setLan(boolean lan) throws IOException {
    this.lan = lan;
    save();
  }

  /**
   * Порт, на котором сайт работал на другом компьютере хоста: адрес в CloudPub ведёт на него.
   * Берём, если он свободен; иначе остаётся прежний.
   */
  public void prefer(int p) throws IOException {
    if (p != port && p >= 1024 && p <= 65535 && free(p)) {
      port = p;
      save();
    }
  }

  /** Сохранённый порт, если он свободен; иначе любой свободный — и он запоминается. */
  public int choosePort() throws IOException {
    if (port < 1024 || port > 65535 || !free(port)) {
      try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
        port = s.getLocalPort();
      }
      save();
    } else if (!Files.exists(file)) {
      save();
    }
    return port;
  }

  private boolean free(int p) {
    try (ServerSocket s = new ServerSocket()) {
      // Как Tomcat: порт, на котором только что работал прежний сервер (TIME_WAIT), свободен.
      s.setReuseAddress(true);
      s.bind(new InetSocketAddress(InetAddress.getByName(bindAddress()), p));
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private void save() throws IOException {
    Properties p = new Properties();
    p.setProperty("port", String.valueOf(port));
    p.setProperty("lan", String.valueOf(lan));
    Files.createDirectories(file.getParent());
    try (OutputStream out = Files.newOutputStream(file)) {
      p.store(out, "groupbase: сеть приложения хоста");
    }
  }
}
