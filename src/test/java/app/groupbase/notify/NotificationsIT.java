package app.groupbase.notify;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.ApiClient;
import app.groupbase.IntegrationTest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Уведомления: колокольчик, настройки, push через поддельную службу на localhost, напоминания. */
@TestPropertySource(properties = "groupbase.push.allowed-hosts=127.0.0.1")
class NotificationsIT extends IntegrationTest {

  record Received(String path, Map<String, List<String>> headers, byte[] body) {}

  static HttpServer server;
  static final BlockingQueue<Received> inbox = new LinkedBlockingQueue<>();
  static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired Reminders reminders;

  @BeforeAll
  static void startPushService() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/push/",
        ex -> {
          byte[] body = ex.getRequestBody().readAllBytes();
          String path = ex.getRequestURI().getPath();
          inbox.add(new Received(path, ex.getRequestHeaders(), body));
          ex.sendResponseHeaders(path.endsWith("/gone") ? 410 : 201, -1);
          ex.close();
        });
    server.start();
  }

  @AfterAll
  static void stopPushService() {
    server.stop(0);
  }

  /** Устройство: ключи браузера и адрес подписки на поддельной службе. */
  record Device(KeyPair keys, byte[] auth, String endpoint) {
    byte[] publicKey() {
      return PushCrypto.encode((ECPublicKey) keys.getPublic());
    }
  }

  private Device subscribe(ApiClient api, String token) {
    Device d =
        new Device(
            PushCrypto.newKeyPair(),
            new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16},
            "http://127.0.0.1:" + server.getAddress().getPort() + "/push/" + token);
    var r =
        api.post(
            "/api/push/subscriptions",
            Map.of(
                "endpoint", d.endpoint(),
                "p256dh", PushCrypto.b64(d.publicKey()),
                "auth", PushCrypto.b64(d.auth()),
                "device", "Chrome, Android"));
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    return d;
  }

  private JsonNode open(Received r, Device d) throws Exception {
    return JSON.readTree(PushCryptoTest.decrypt(r.body(), d.keys(), d.publicKey(), d.auth()));
  }

  private long subject(ApiClient api, long group, String name) {
    return api.post("/api/groups/" + group + "/subjects", Map.of("name", name))
        .json()
        .get("id")
        .asLong();
  }

  private long homework(ApiClient api, long subject, long group, String title, Duration dueIn) {
    var r =
        api.post(
            "/api/homework",
            Map.of(
                "subjectId",
                subject,
                "title",
                title,
                "body",
                "",
                "dueAt",
                clock.millis() + dueIn.toMillis(),
                "groupIds",
                List.of(group)));
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    return r.json().get("id").asLong();
  }

  private static int unread(TestUser u) {
    return u.api().get("/api/notifications/unread").json().get("count").asInt();
  }

  /** Рассылка идёт в фоне: ждём, пока счётчик дойдёт до нужного значения. */
  private static void awaitUnread(TestUser u, int expected) throws InterruptedException {
    long until = System.currentTimeMillis() + 5000;
    while (unread(u) != expected && System.currentTimeMillis() < until) {
      Thread.sleep(25);
    }
    assertThat(unread(u)).isEqualTo(expected);
  }

  /** Push для конкретного устройства; чужие (например, из прошлых тестов) пропускаем. */
  private static Received awaitPush(Device d) throws InterruptedException {
    long until = System.currentTimeMillis() + 5000;
    while (System.currentTimeMillis() < until) {
      Received r = inbox.poll(100, TimeUnit.MILLISECONDS);
      if (r != null && d.endpoint().endsWith(r.path())) {
        return r;
      }
    }
    throw new AssertionError("push для " + d.endpoint() + " не пришёл");
  }

  @Test
  void newHomeworkGoesToBellAndEncryptedPush() throws Exception {
    inbox.clear();
    long g = newGroup("Уведомления");
    long other = newGroup("Чужие");
    TestUser headman = newUser(g, "headman");
    TestUser withPush = newUser(g, "student");
    TestUser noPush = newUser(g, "student");
    TestUser stranger = newUser(other, "student");
    Device phone = subscribe(withPush.api(), "phone-" + uniq());
    long s = subject(headman.api(), g, "Физика");
    long hw = homework(headman.api(), s, g, "Лабораторная 3", Duration.ofDays(3));

    awaitUnread(withPush, 1);
    awaitUnread(noPush, 1);
    assertThat(unread(headman)).as("автору не сообщаем").isZero();
    assertThat(unread(stranger)).isZero();
    var item = noPush.api().get("/api/notifications").json().get("items").get(0);
    assertThat(item.get("title").asString()).isEqualTo("Новое задание · Физика");
    assertThat(item.get("url").asString()).isEqualTo("/homework/" + hw);

    Received r = awaitPush(phone);
    assertThat(r.headers().get("Content-encoding")).containsExactly("aes128gcm");
    assertThat(r.headers().get("Ttl")).containsExactly("86400");
    String auth = r.headers().get("Authorization").getFirst();
    String publicKey =
        withPush.api().get("/api/me/notifications").json().get("publicKey").asString();
    assertThat(auth).startsWith("vapid t=").endsWith(", k=" + publicKey);
    String[] jwt = auth.substring(8, auth.indexOf(',')).split("\\.");
    JsonNode claims = JSON.readTree(PushCrypto.unb64(jwt[1]));
    assertThat(claims.get("aud").asString())
        .isEqualTo("http://127.0.0.1:" + server.getAddress().getPort());
    Signature v = Signature.getInstance("SHA256withECDSAinP1363Format");
    v.initVerify(PushCrypto.publicKey(PushCrypto.unb64(publicKey)));
    v.update((jwt[0] + "." + jwt[1]).getBytes(StandardCharsets.US_ASCII));
    assertThat(v.verify(PushCrypto.unb64(jwt[2]))).isTrue();

    JsonNode msg = open(r, phone);
    assertThat(msg.get("title").asString()).isEqualTo("Новое задание · Физика");
    assertThat(msg.get("body").asString()).startsWith("Лабораторная 3 — сдать до ");
    assertThat(msg.get("url").asString()).isEqualTo("/homework/" + hw);
    assertThat(inbox.poll(300, TimeUnit.MILLISECONDS)).as("одно устройство — один push").isNull();
  }

  @Test
  void settingsDecidePushButBellStillShowsEverything() throws Exception {
    inbox.clear();
    long g = newGroup("Настройки");
    TestUser headman = newUser(g, "headman");
    TestUser student = newUser(g, "student");
    Device phone = subscribe(student.api(), "quiet-" + uniq());
    var saved =
        student.api().put("/api/me/notifications", Map.of("homework", false, "news", "urgent"));
    assertThat(saved.json().get("prefs").get("news").asString()).isEqualTo("urgent");
    assertThat(student.api().put("/api/me/notifications", Map.of("news", "loud")).status())
        .isEqualTo(400);

    long s = subject(headman.api(), g, "История");
    homework(headman.api(), s, g, "Реферат", Duration.ofDays(5));
    headman
        .api()
        .post("/api/news", Map.of("title", "Обычная новость", "body", "", "groupIds", List.of(g)));
    awaitUnread(student, 2);
    assertThat(inbox.poll(500, TimeUnit.MILLISECONDS)).as("эти виды push выключены").isNull();

    headman
        .api()
        .post(
            "/api/news",
            Map.of("title", "Пара отменена", "body", "", "urgent", true, "groupIds", List.of(g)));
    Received r = awaitPush(phone);
    assertThat(r.headers().get("Urgency")).containsExactly("high");
    awaitUnread(student, 3);
    assertThat(open(r, phone).get("title").asString()).isEqualTo("Срочно: Пара отменена");

    // Прочитать одно и все сразу.
    var page = student.api().get("/api/notifications?limit=2").json();
    assertThat(page.get("items").size()).isEqualTo(2);
    assertThat(page.get("next").isNull()).isFalse();
    long newest = page.get("items").get(0).get("id").asLong();
    assertThat(
            student
                .api()
                .post("/api/notifications/read", Map.of("ids", List.of(newest)))
                .json()
                .get("count")
                .asInt())
        .isEqualTo(2);
    student.api().post("/api/notifications/read", Map.of("all", true));
    assertThat(unread(student)).isZero();
  }

  @Test
  void subscriptionsAreCheckedAndDeadOnesRemoved() throws Exception {
    inbox.clear();
    long g = newGroup("Подписки");
    TestUser u = newUser(g, "student");
    var evil =
        u.api()
            .post(
                "/api/push/subscriptions",
                Map.of(
                    "endpoint", "https://evil.example.com/steal",
                    "p256dh", PushCryptoTest.UA_PUBLIC,
                    "auth", PushCryptoTest.AUTH));
    assertThat(evil.status()).as("чужие адреса не принимаем").isEqualTo(400);
    var badKey =
        u.api()
            .post(
                "/api/push/subscriptions",
                Map.of(
                    "endpoint", "http://127.0.0.1:1/push/x",
                    "p256dh", "AAAA",
                    "auth", PushCryptoTest.AUTH));
    assertThat(badKey.status()).isEqualTo(400);

    Device gone = subscribe(u.api(), "gone");
    assertThat(u.api().get("/api/me/notifications").json().get("devices").size()).isEqualTo(1);
    var test = u.api().post("/api/push/test", null).json();
    assertThat(test.get("devices").asInt()).isEqualTo(1);
    assertThat(test.get("delivered").asInt()).isZero();
    awaitPush(gone);
    assertThat(u.api().get("/api/me/notifications").json().get("devices").size())
        .as("служба ответила 410 — подписка удалена")
        .isZero();

    Device d = subscribe(u.api(), "ok-" + uniq());
    assertThat(u.api().post("/api/push/test", null).json().get("delivered").asInt()).isEqualTo(1);
    assertThat(open(awaitPush(d), d).get("title").asString()).isEqualTo("Уведомления работают");
    long id = u.api().get("/api/me/notifications").json().get("devices").get(0).get("id").asLong();
    assertThat(u.api().delete("/api/push/devices/" + id).status()).isEqualTo(200);
    assertThat(u.api().delete("/api/push/devices/" + id).status()).isEqualTo(404);
  }

  @Test
  void remindersDayBeforeAndMorningDigest() throws Exception {
    long g = newGroup("Напоминания");
    TestUser headman = newUser(g, "headman");
    TestUser lazy = newUser(g, "student");
    TestUser done = newUser(g, "student");
    long s = subject(headman.api(), g, "Матанализ");
    long hw = homework(headman.api(), s, g, "Типовой расчёт", Duration.ofDays(3));
    long urgent = homework(headman.api(), s, g, "Срочная задача", Duration.ofHours(10));
    done.api().put("/api/homework/" + hw + "/done", Map.of("value", true));
    awaitUnread(lazy, 2);
    lazy.api().post("/api/notifications/read", Map.of("all", true));

    clock.advance(Duration.ofDays(2).plusHours(1));
    reminders.dayBefore(clock.millis());
    var items = lazy.api().get("/api/notifications").json().get("items");
    assertThat(items.get(0).get("title").asString()).isEqualTo("Скоро срок · Матанализ");
    assertThat(items.get(0).get("body").asString()).startsWith("Типовой расчёт — сдать ");
    assertThat(unread(lazy)).as("только про задание, выложенное заранее").isEqualTo(1);
    assertThat(done.api().get("/api/notifications").json().get("items").findValuesAsString("title"))
        .doesNotContain("Скоро срок · Матанализ");
    reminders.dayBefore(clock.millis());
    assertThat(unread(lazy)).as("повторно не напоминаем").isEqualTo(1);

    // Сводка: в выбранное время, раз в день и только тем, у кого есть устройство.
    inbox.clear();
    Device phone = subscribe(lazy.api(), "digest-" + uniq());
    ZonedDateTime local = Instant.ofEpochMilli(clock.millis()).atZone(props.timezone());
    int minute = local.getHour() * 60 + local.getMinute();
    lazy.api().put("/api/me/notifications", Map.of("digest", true, "digestAt", minute));
    assertThat(reminders.digest(clock.millis())).isGreaterThanOrEqualTo(1);
    JsonNode msg = open(awaitPush(phone), phone);
    assertThat(msg.get("title").asString()).matches("(Сегодня|Завтра) сдать 1 задание");
    assertThat(msg.get("body").asString()).isEqualTo("Просрочено — 1");
    assertThat(reminders.digest(clock.millis())).as("раз в день").isZero();
    lazy.api().put("/api/homework/" + hw + "/done", Map.of("value", true));
    lazy.api().put("/api/homework/" + urgent + "/done", Map.of("value", true));
    clock.advance(Duration.ofDays(1));
    assertThat(reminders.digest(clock.millis())).as("всё сдано — молчим").isZero();
  }

  @Test
  void digestTexts() {
    assertThat(Reminders.digestMessage(0, 0, 0)).isNull();
    assertThat(Reminders.digestMessage(2, 1, 1).title()).isEqualTo("Сегодня сдать 2 задания");
    assertThat(Reminders.digestMessage(2, 1, 1).body()).isEqualTo("Завтра — 1, просрочено — 1");
    assertThat(Reminders.digestMessage(0, 5, 0).title()).isEqualTo("Завтра сдать 5 заданий");
    assertThat(Reminders.digestMessage(0, 0, 3).title()).isEqualTo("Есть просроченные задания");
    assertThat(Reminders.tasks(21)).isEqualTo("задание");
    assertThat(Reminders.tasks(12)).isEqualTo("заданий");
  }
}
