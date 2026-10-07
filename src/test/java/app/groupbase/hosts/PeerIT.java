package app.groupbase.hosts;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.ApiClient;
import app.groupbase.GroupbaseApplication;
import app.groupbase.IntegrationTest;
import app.groupbase.accounts.PublicUrl;
import app.groupbase.backup.PendingRestore;
import app.groupbase.desktop.DesktopBridge;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Два компьютера хоста напрямую: этот сервер — «главный» A, второй сервер B поднимается в тесте.
 * Адрес сайта — прокси, который изображает туннель: пересылает главному, «нет интернета» (обрыв)
 * или «компьютер выключен» (страница туннеля без метки groupbase).
 */
class PeerIT extends IntegrationTest {

  @DynamicPropertySource
  static void desktop(DynamicPropertyRegistry r) {
    r.add("groupbase.desktop.enabled", () -> "true");
    r.add("groupbase.hosts.tick-ms", () -> "3600000");
    r.add("groupbase.peers.tick-ms", () -> "0");
    r.add("groupbase.peers.takeover-ms", () -> "0");
    r.add("groupbase.peers.discovery-port", () -> String.valueOf(DISCOVERY_PORT));
  }

  static final int DISCOVERY_PORT = freeUdpPort();

  private static int freeUdpPort() {
    try (var s = new java.net.DatagramSocket(0)) {
      return s.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Autowired PeerService peers;
  @Autowired PublicUrl publicUrl;
  @Autowired DesktopBridge bridge;

  /** Туннель: куда ведёт адрес сайта и что с ним сейчас. */
  static final class Tunnel {
    volatile int target;
    volatile String mode = "forward";
    private final HttpServer server;
    private final HttpClient http =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private static final Set<String> SKIP =
        Set.of("connection", "content-length", "expect", "host", "upgrade", "transfer-encoding");

    Tunnel(int target) throws IOException {
      this.target = target;
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", this::handle);
      server.start();
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
      server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
      try (ex) {
        if (mode.equals("offline")) {
          return; // соединение рвётся без ответа — как без интернета
        }
        if (mode.equals("nobody")) {
          byte[] page = "<h1>Tunnel not found</h1>".getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "text/html");
          ex.sendResponseHeaders(404, page.length);
          ex.getResponseBody().write(page);
          return;
        }
        byte[] body = ex.getRequestBody().readAllBytes();
        HttpRequest.Builder b =
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + target + ex.getRequestURI()))
                .method(
                    ex.getRequestMethod(),
                    body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        ex.getRequestHeaders()
            .forEach(
                (k, vs) -> {
                  if (!SKIP.contains(k.toLowerCase())) {
                    vs.forEach(v -> b.header(k, v));
                  }
                });
        // Через туннель запросы приходят «снаружи».
        b.header("X-Forwarded-For", "203.0.113.9");
        HttpResponse<byte[]> r;
        try {
          r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        r.headers()
            .map()
            .forEach(
                (k, vs) -> {
                  if (!SKIP.contains(k.toLowerCase()) && !k.startsWith(":")) {
                    vs.forEach(v -> ex.getResponseHeaders().add(k, v));
                  }
                });
        ex.sendResponseHeaders(r.statusCode(), r.body().length == 0 ? -1 : r.body().length);
        try (OutputStream out = ex.getResponseBody()) {
          out.write(r.body());
        }
      }
    }
  }

  private ApiClient window() {
    admin();
    ApiClient c = client();
    URI u = URI.create(bridge.enterUrl());
    var r = c.get(u.getRawPath() + "?" + u.getRawQuery());
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    return c;
  }

  private static ConfigurableApplicationContext startB(Path data) {
    return new SpringApplicationBuilder(GroupbaseApplication.class)
        .properties(
            "groupbase.data-dir=" + data,
            "server.port=0",
            "groupbase.http.insecure=true",
            "groupbase.auth.require-staff-totp=false",
            "groupbase.desktop.enabled=true",
            "groupbase.hosts.tick-ms=3600000",
            "groupbase.peers.tick-ms=0",
            "groupbase.peers.takeover-ms=0",
            "groupbase.peers.discovery-port=0",
            "groupbase.update-check.enabled=false")
        .run();
  }

  private static int portOf(ConfigurableApplicationContext ctx) {
    return ((WebServerApplicationContext) ctx).getWebServer().getPort();
  }

  private static List<String> groups(ApiClient c) {
    var r = c.get("/api/groups");
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    return r.json().valueStream().map(n -> n.get("name").asString()).toList();
  }

  private static byte[] png() throws IOException {
    BufferedImage img = new BufferedImage(48, 32, BufferedImage.TYPE_INT_RGB);
    for (int x = 0; x < 48; x++) {
      for (int y = 0; y < 32; y++) {
        img.setRGB(x, y, (x * 5) << 16 | (y * 7) << 8 | 90);
      }
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(img, "png", out);
    return out.toByteArray();
  }

  @Test
  void twoComputersStayInSyncAndHandOverTheSite() throws Exception {
    Tunnel tunnel = new Tunnel(port);
    publicUrl.set(tunnel.url());
    ApiClient a = admin();
    ApiClient wa = window();
    ConfigurableApplicationContext ctxB = null;
    try {
      // Код даёт администратор; этот компьютер становится главным.
      var code = wa.post("/api/host/peers/code", Map.of());
      assertThat(code.status()).as(code.body()).isEqualTo(200);
      // Код сам по себе основным не делает (0.9.7): иначе показавшие код на обоих компьютерах
      // оставались двумя «основными» без связи и не могли подключиться друг к другу.
      assertThat(code.json().get("role").asString()).isEqualTo("off");
      String pairing = code.json().get("code").get("code").asString();

      // Код переноса и код связи выглядят одинаково: код переноса для связи не годится — понятно
      // почему.
      var moveCode = a.post("/api/host/transfer/code", Map.of());
      assertThat(moveCode.status()).as(moveCode.body()).isEqualTo(200);
      var wrongKind =
          client()
              .post(
                  "/api/host/peer/pair",
                  Map.of(
                      "code",
                      moveCode.json().get("code").asString(),
                      "computer",
                      "11111111-2222-3333-4444-555555555555",
                      "name",
                      "Чужой"));
      assertThat(wrongKind.body()).contains("код переноса");
      assertThat(a.delete("/api/host/transfer/code").status()).isLessThan(300);

      // Второй компьютер подключается при первом запуске — код связи введён в форму «Перенос по
      // коду»: сервер сам понимает, что это код связи. Ключ, полная копия, перезапуск.
      Path dataB = Files.createTempDirectory("groupbase-peer-b");
      ctxB = startB(dataB);
      PeerService peersB = ctxB.getBean(PeerService.class);
      String setupB = ctxB.getBean(app.groupbase.accounts.SetupService.class).setupCode();
      var joined =
          new ApiClient(portOf(ctxB))
              .post(
                  "/api/setup/transfer?code=" + setupB,
                  Map.of("url", tunnel.url(), "code", pairing));
      assertThat(joined.status()).as(joined.body()).isEqualTo(200);
      assertThat(joined.body()).contains("перезапускаемся");
      ctxB.close();
      assertThat(PendingRestore.apply(dataB, s -> {})).isTrue();
      ctxB = startB(dataB);
      peersB = ctxB.getBean(PeerService.class);
      assertThat(peersB.role()).isEqualTo(PeerService.Role.SECOND);
      assertThat(peers.role()).isEqualTo(PeerService.Role.MAIN);
      ApiClient b = new ApiClient(portOf(ctxB));
      var login = b.post("/api/auth/login", Map.of("username", ADMIN, "password", ADMIN_PASSWORD));
      assertThat(login.status()).as(login.body()).isEqualTo(200);

      // Новое на главном — на втором после шага синхронизации; сессия второго не пропадает.
      String fromA = "С главного " + uniq();
      assertThat(a.post("/api/groups", Map.of("name", fromA, "university", "МТУСИ")).status())
          .isEqualTo(200);
      peersB.tick();
      assertThat(groups(b)).contains(fromA);

      // Изменение во втором окне уходит главному, и второй видит его сразу.
      String fromB = "Со второго " + uniq();
      var made = b.post("/api/groups", Map.of("name", fromB, "university", "МТУСИ"));
      assertThat(made.status()).as(made.body()).isEqualTo(200);
      assertThat(made.raw().headers().firstValue("X-Groupbase-Queued")).isEmpty();
      assertThat(groups(a)).contains(fromB);
      assertThat(groups(b)).contains(fromB);

      // Администратор на копии управляет сайтом, но доступ для группы и копии данных – только на
      // основном (0.9.7).
      var hostOnly = b.put("/api/admin/access", Map.of("mode", "lan"));
      assertThat(hostOnly.status()).as(hostOnly.body()).isEqualTo(409);
      assertThat(hostOnly.body()).contains("host_only");

      // Файл со второго — у главного, и у второго тоже.
      var bg = b.putRaw("/api/me/background", png());
      assertThat(bg.status()).as(bg.body()).isEqualTo(200);
      String bgId = bg.json().get("background").asString();
      assertThat(props.dataDir().resolve("avatars").resolve(bgId + "-1920.webp")).exists();
      peersB.tick();
      assertThat(dataB.resolve("avatars").resolve(bgId + "-1920.webp")).exists();

      // Нет связи: изменение применяется здесь и ждёт; связь появилась — уходит главному.
      tunnel.mode = "offline";
      String offline = "Без связи " + uniq();
      var queued = b.post("/api/groups", Map.of("name", offline, "university", "МТУСИ"));
      assertThat(queued.status()).as(queued.body()).isEqualTo(200);
      assertThat(queued.raw().headers().firstValue("X-Groupbase-Queued")).hasValue("1");
      assertThat(groups(b)).contains(offline);
      assertThat(groups(a)).doesNotContain(offline);
      peersB.tick();
      assertThat(peersB.view().state()).isEqualTo("offline");
      assertThat(peersB.view().queued()).isEqualTo(1);
      tunnel.mode = "forward";
      peersB.tick();
      assertThat(peersB.view().queued()).isZero();
      assertThat(groups(a)).contains(offline);
      assertThat(groups(b)).contains(offline);

      // Чужой ключ — не пускают; второй сам данные не отдаёт.
      assertThat(
              client()
                  .header("X-Groupbase-Peer", "00000000-0000 " + "x".repeat(43))
                  .get("/api/host/peer/state")
                  .status())
          .isEqualTo(403);

      // Главный выключен (по адресу — страница туннеля): второй становится главным.
      tunnel.mode = "nobody";
      peersB.tick();
      assertThat(peersB.role()).isEqualTo(PeerService.Role.MAIN);
      assertThat(peersB.view().epoch()).isEqualTo(2);

      // Прежний главный без связи успел что-то изменить в своём окне.
      String onOld = "На старом " + uniq();
      assertThat(wa.post("/api/groups", Map.of("name", onOld, "university", "МТУСИ")).status())
          .isEqualTo(200);

      // Туннель теперь ведёт на B; прежний главный это видит, отдаёт своё и становится вторым.
      tunnel.target = portOf(ctxB);
      tunnel.mode = "forward";
      peers.syncNow();
      assertThat(peers.role()).isEqualTo(PeerService.Role.SECOND);
      assertThat(groups(b)).contains(onOld, fromA, fromB, offline);
      assertThat(groups(a)).contains(onOld);
      try (var aside = Files.list(props.dataDir().resolve("peer").resolve("aside"))) {
        assertThat(aside.count()).isEqualTo(1);
      }

      // Компьютеры равны: «Перенести сайт сюда» с A, когда B включён, — B отдаёт свежую базу и
      // становится копией, ничего не теряется.
      String lastOnB = "Последнее на B " + uniq();
      assertThat(b.post("/api/groups", Map.of("name", lastOnB, "university", "МТУСИ")).status())
          .isEqualTo(200);
      var here = wa.post("/api/host/peers/here", Map.of());
      assertThat(here.status()).as(here.body()).isEqualTo(200);
      assertThat(peers.role()).isEqualTo(PeerService.Role.MAIN);
      assertThat(peersB.role()).isEqualTo(PeerService.Role.SECOND);
      assertThat(peers.view().epoch()).isEqualTo(3);
      assertThat(groups(a)).contains(lastOnB);
      // Адрес сайта теперь ведёт на A; B выравнивается по нему.
      tunnel.target = port;
      String afterMove = "После переезда " + uniq();
      assertThat(a.post("/api/groups", Map.of("name", afterMove, "university", "МТУСИ")).status())
          .isEqualTo(200);
      peersB.tick();
      assertThat(groups(b)).contains(afterMove, lastOnB);
      assertThat(peersB.view().serving()).isEqualTo(peers.view().computer());

      // «Сделать хостом» на компьютере, где сайт: выбрали B — B сам забирает сайт.
      var chosen = wa.post("/api/host/peers/host", Map.of("computer", peersB.computerId()));
      assertThat(chosen.status()).as(chosen.body()).isEqualTo(200);
      peersB.tick();
      for (int i = 0; i < 50 && peersB.role() != PeerService.Role.MAIN; i++) {
        Thread.sleep(100);
      }
      assertThat(peersB.role()).isEqualTo(PeerService.Role.MAIN);
      assertThat(peers.role()).isEqualTo(PeerService.Role.SECOND);
      // И обратно — с этого компьютера, «Сделать хостом» себя.
      tunnel.target = portOf(ctxB);
      var back = wa.post("/api/host/peers/host", Map.of("computer", peers.computerId()));
      assertThat(back.status()).as(back.body()).isEqualTo(200);
      assertThat(peers.role()).isEqualTo(PeerService.Role.MAIN);
      tunnel.target = port;

      // Без кода: компьютер с сайтом отвечает на поиск в локальной сети адресом сайта.
      try (var udp = new java.net.DatagramSocket()) {
        udp.setSoTimeout(3000);
        byte[] ask = PeerDiscovery.MAGIC.getBytes(StandardCharsets.US_ASCII);
        udp.send(
            new java.net.DatagramPacket(
                ask, ask.length, java.net.InetAddress.getLoopbackAddress(), DISCOVERY_PORT));
        var reply = new java.net.DatagramPacket(new byte[2048], 2048);
        udp.receive(reply);
        PeerDiscovery.Found f =
            PeerDiscovery.parse(
                new String(reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8));
        assertThat(f).isNotNull();
        assertThat(f.url()).isEqualTo(tunnel.url());
      }

      // Третий компьютер знает только адрес: просит подключиться, здесь «Разрешить» — и всё.
      // Приложение хоста занимает свой порт (8080 и дальше) — B больше не нужен.
      ctxB.close();
      ctxB = null;
      Path dataC = Files.createTempDirectory("groupbase-peer-c");
      ConfigurableApplicationContext ctxC = startB(dataC);
      try {
        PeerService peersC = ctxC.getBean(PeerService.class);
        peersC.startAsk(tunnel.url());
        for (int i = 0; i < 50 && peers.requests().isEmpty(); i++) {
          Thread.sleep(100);
        }
        assertThat(peers.requests()).hasSize(1);
        // Ответ из диалога оболочки приходит командой peer-allow.
        bridge.peerAnswer(peers.requests().get(0).id(), true);
        for (int i = 0; i < 100 && peersC.role() != PeerService.Role.SECOND; i++) {
          Thread.sleep(100);
        }
        assertThat(peersC.ask().phase()).as(String.valueOf(peersC.ask())).isEqualTo("joining");
        assertThat(peersC.role()).isEqualTo(PeerService.Role.SECOND);
      } finally {
        ctxC.close();
      }

      // Ключ сайта: вставили один раз — подключился сразу, без «Разрешить».
      var key = wa.get("/api/host/peers/key");
      assertThat(key.status()).as(key.body()).isEqualTo(200);
      String siteKey = key.json().get("key").asString();
      assertThat(siteKey).startsWith("campus-");
      Path dataD = Files.createTempDirectory("groupbase-peer-d");
      ConfigurableApplicationContext ctxD = startB(dataD);
      try {
        PeerService peersD = ctxD.getBean(PeerService.class);
        assertThat(peersD.joinByKey(siteKey)).contains("перезапускаемся");
        assertThat(peersD.role()).isEqualTo(PeerService.Role.SECOND);
        // Сменили ключ — старый больше не подключает.
        assertThat(wa.post("/api/host/peers/key", Map.of()).status()).isEqualTo(200);
        var wrong =
            client()
                .post(
                    "/api/host/peer/pair",
                    Map.of(
                        "code",
                        PeerService.parseKey(siteKey)[1],
                        "computer",
                        "22222222-3333-4444-5555-666666666666",
                        "name",
                        "Старый ключ"));
        assertThat(wrong.status()).isEqualTo(400);
      } finally {
        ctxD.close();
      }

      // Отвязали все компьютеры – этот снова «не связан» и может сам подключиться к другому сайту.
      for (PeerService.Peer p : peers.view().peers()) {
        if (!p.here()) {
          peers.removePeer(p.computerId());
        }
      }
      assertThat(peers.role()).isEqualTo(PeerService.Role.OFF);
      assertThat(peers.view().role()).isEqualTo("off");
    } finally {
      if (ctxB != null) {
        ctxB.close();
      }
      tunnel.stop();
    }
  }
}
