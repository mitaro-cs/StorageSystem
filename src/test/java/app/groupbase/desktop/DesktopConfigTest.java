package app.groupbase.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopConfigTest {

  @Test
  void keepsPortWhileFreeAndMovesWhenBusy(@TempDir Path data) throws IOException {
    int port;
    try (ServerSocket probe = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      port = probe.getLocalPort();
    }
    java.nio.file.Files.writeString(data.resolve(DesktopConfig.FILE), "port=" + port + "\n");
    assertThat(DesktopConfig.load(data).choosePort()).isEqualTo(port);

    try (ServerSocket busy = new ServerSocket(port, 0, InetAddress.getLoopbackAddress())) {
      assertThat(busy.isBound()).isTrue();
      int moved = DesktopConfig.load(data).choosePort();
      assertThat(moved).isNotEqualTo(port);
      // Новый порт запомнен: при следующем запуске окно хоста откроется по тому же адресу.
      assertThat(DesktopConfig.load(data).port()).isEqualTo(moved);
    }
  }

  @Test
  void lanSwitchIsPersisted(@TempDir Path data) throws IOException {
    DesktopConfig c = DesktopConfig.load(data);
    assertThat(c.lan()).isFalse();
    assertThat(c.bindAddress()).isEqualTo("127.0.0.1");
    c.setLan(true);
    assertThat(DesktopConfig.load(data).bindAddress()).isEqualTo("0.0.0.0");
  }
}
