package app.groupbase.status;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.TestClock;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class UpdateCheckTest {

  private HttpServer github;
  private final AtomicInteger calls = new AtomicInteger();
  private volatile int status = 200;
  private volatile String body =
      "{\"tag_name\":\"v0.5.0\",\"html_url\":\"https://example.test/v0.5.0\"}";
  private final TestClock clock = new TestClock(Instant.parse("2026-09-26T10:00:00Z"));

  /** Поддельный GitHub на свободном порту: отвечает тем, что задал тест. */
  private URI github() throws Exception {
    github = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    github.createContext(
        "/latest",
        ex -> {
          calls.incrementAndGet();
          byte[] b = body.getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(status, b.length);
          ex.getResponseBody().write(b);
          ex.close();
        });
    github.start();
    return URI.create("http://127.0.0.1:" + github.getAddress().getPort() + "/latest");
  }

  @AfterEach
  void stop() {
    if (github != null) {
      github.stop(0);
    }
  }

  @Test
  void comparesVersions() {
    assertThat(UpdateCheck.newer("0.3.0", "0.2.9")).isTrue();
    assertThat(UpdateCheck.newer("1.0", "0.9.9")).isTrue();
    assertThat(UpdateCheck.newer("0.2.0", "0.2.0")).isFalse();
    assertThat(UpdateCheck.newer("0.2.0", "0.10.0")).isFalse();
    assertThat(UpdateCheck.newer("v0.2.1", "0.2.0-SNAPSHOT")).isTrue();
  }

  @Test
  void checkNowAsksGithubAndRemembersTheLatestRelease() throws Exception {
    UpdateCheck u = new UpdateCheck(true, clock, github(), () -> "0.4.6");
    var r = u.checkNow();
    assertThat(r.latest()).isEqualTo("0.5.0");
    assertThat(r.error()).isNull();
    assertThat(r.checkedAt()).isEqualTo(clock.millis());
    assertThat(u.available().version()).isEqualTo("0.5.0");

    // Нажали ещё раз сразу — ответ тот же, GitHub второй раз не спрашиваем.
    u.checkNow();
    assertThat(calls.get()).isEqualTo(1);
    clock.advance(Duration.ofSeconds(20));
    u.checkNow();
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void latestVersionIsNotAnUpdate() throws Exception {
    UpdateCheck u = new UpdateCheck(true, clock, github(), () -> "0.5.0");
    assertThat(u.checkNow().latest()).isEqualTo("0.5.0");
    assertThat(u.available()).isNull();
  }

  @Test
  void explainsWhyTheCheckFailed() throws Exception {
    URI url = github();
    status = 403;
    UpdateCheck u = new UpdateCheck(true, clock, url, () -> "0.4.6");
    assertThat(u.checkNow().error()).contains("ограничил");

    // Ошибку проверяем заново при следующем нажатии, не дожидаясь 15 секунд.
    status = 200;
    body = "{}";
    assertThat(u.checkNow().error()).contains("не назвал");
    body = "не json";
    assertThat(u.checkNow().error()).contains("непонятный");
    assertThat(calls.get()).isEqualTo(3);

    // Сервера нет — нет связи.
    github.stop(0);
    github = null;
    assertThat(u.checkNow().error()).contains("нет связи");
  }

  @Test
  void disabledOrDevBuildsDoNotAskGithub() throws Exception {
    URI url = github();
    assertThat(new UpdateCheck(false, clock, url, () -> "0.4.6").checkNow().error())
        .contains("выключена");
    assertThat(new UpdateCheck(true, clock, url, () -> "dev").checkNow().error())
        .contains("для разработки");
    assertThat(new UpdateCheck(true, clock, url, () -> "dev").available()).isNull();
    assertThat(calls.get()).isZero();
  }
}
