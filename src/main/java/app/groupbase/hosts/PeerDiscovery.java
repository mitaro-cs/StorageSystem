package app.groupbase.hosts;

import app.groupbase.desktop.DesktopBridge;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

/**
 * Поиск сайта в локальной сети: новый компьютер шлёт широковещательный UDP-вопрос, компьютер с
 * сайтом отвечает адресом сайта и своим именем. Дальше — обычный запрос на подключение по адресу
 * сайта с подтверждением там ({@link PeerService#request}). Ни кодов, ни ключей по UDP не ходит.
 */
@Component
public class PeerDiscovery implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(PeerDiscovery.class);
  static final int PORT = 17391;
  static final String MAGIC = "campus-discover-1";
  private static final int MAX_PACKET = 2048;

  /** Найденный сайт. */
  public record Found(String site, String computer, String name, String url) {}

  private final PeerService peers;
  private final DesktopBridge bridge;
  private final int port;
  private volatile DatagramSocket socket;
  private volatile boolean running;

  public PeerDiscovery(PeerService peers, DesktopBridge bridge, Environment env) {
    this.peers = peers;
    this.bridge = bridge;
    // 0 — не отвечать на поиск (тесты с несколькими серверами задают свой порт).
    this.port = env.getProperty("groupbase.peers.discovery-port", Integer.class, PORT);
  }

  int port() {
    return port;
  }

  /** Сайты в локальной сети (за полторы секунды), без этого компьютера. */
  public List<Found> discover() {
    Map<String, Found> found = new LinkedHashMap<>();
    if (port <= 0) {
      return List.of();
    }
    byte[] ask = MAGIC.getBytes(StandardCharsets.US_ASCII);
    try (DatagramSocket s = new DatagramSocket()) {
      s.setBroadcast(true);
      for (InetAddress to : targets()) {
        try {
          s.send(new DatagramPacket(ask, ask.length, to, port));
        } catch (IOException e) {
          // сеть без широковещания — пропускаем
        }
      }
      long deadline = System.currentTimeMillis() + 1500;
      byte[] buf = new byte[MAX_PACKET];
      while (true) {
        long left = deadline - System.currentTimeMillis();
        if (left <= 0) {
          break;
        }
        s.setSoTimeout((int) left);
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        try {
          s.receive(p);
        } catch (SocketTimeoutException e) {
          break;
        }
        Found f = parse(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
        if (f != null && !f.computer().equals(peers.computerId())) {
          found.putIfAbsent(f.site() + " " + f.computer(), f);
        }
      }
    } catch (IOException e) {
      log.warn("Поиск в локальной сети не удался: {}", e.getMessage());
    }
    return new ArrayList<>(found.values());
  }

  static Found parse(String json) {
    try {
      Map<String, String> m = SiteFolder.JSON.readValue(json, new TypeReference<>() {});
      String url = m.getOrDefault("url", "");
      String site = m.getOrDefault("site", "");
      String computer = m.getOrDefault("computer", "");
      if (url.isBlank() || site.isBlank() || computer.isBlank()) {
        return null;
      }
      TransferClient.normalize(url);
      String name = m.getOrDefault("name", "");
      return new Found(site, computer, name.length() > 40 ? name.substring(0, 40) : name, url);
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  /** Куда спрашивать: широковещательные адреса сетей этого компьютера и он сам. */
  private static Set<InetAddress> targets() {
    Set<InetAddress> out = new LinkedHashSet<>();
    try {
      for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!ni.isUp() || ni.isLoopback()) {
          continue;
        }
        for (InterfaceAddress a : ni.getInterfaceAddresses()) {
          if (a.getBroadcast() != null) {
            out.add(a.getBroadcast());
          }
        }
      }
    } catch (SocketException e) {
      // список сетей недоступен — хватит общего адреса
    }
    try {
      out.add(InetAddress.getByName("255.255.255.255"));
      out.add(InetAddress.getLoopbackAddress());
    } catch (IOException e) {
      // не бывает
    }
    return out;
  }

  private void serve(DatagramSocket s) {
    byte[] buf = new byte[MAX_PACKET];
    while (running) {
      DatagramPacket p = new DatagramPacket(buf, buf.length);
      try {
        s.receive(p);
      } catch (IOException e) {
        if (running) {
          log.warn("Поиск в локальной сети остановлен: {}", e.getMessage());
        }
        return;
      }
      String got = new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII);
      if (!MAGIC.equals(got)) {
        continue;
      }
      peers
          .discoverable()
          .ifPresent(
              info -> {
                try {
                  byte[] reply = SiteFolder.JSON.writeValueAsBytes(info);
                  s.send(new DatagramPacket(reply, reply.length, p.getSocketAddress()));
                } catch (IOException e) {
                  // тот компьютер уже не слушает
                }
              });
    }
  }

  @Override
  public synchronized void start() {
    running = true;
    if (!bridge.enabled() || port <= 0) {
      return;
    }
    try {
      DatagramSocket s = new DatagramSocket(null);
      s.setReuseAddress(true);
      s.bind(new InetSocketAddress(port));
      socket = s;
      Thread.ofVirtual().name("peer-discovery").start(() -> serve(s));
    } catch (IOException e) {
      // Порт занят (второй сервер на этом компьютере) — просто не отвечаем на поиск.
      log.info("Поиск в локальной сети не включён: {}", e.getMessage());
    }
  }

  @Override
  public synchronized void stop() {
    running = false;
    if (socket != null) {
      socket.close();
      socket = null;
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
