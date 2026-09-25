package app.groupbase.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.TestClock;
import app.groupbase.TestProps;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DesktopBridgeTest {

  private final TestClock clock = new TestClock(Instant.parse("2026-09-24T10:00:00Z"));

  private DesktopBridge bridge(boolean enabled) {
    return new DesktopBridge(
        TestProps.of(
            Map.of(
                "groupbase.desktop.enabled",
                String.valueOf(enabled),
                "groupbase.http.port",
                "17380")),
        clock);
  }

  private static String token(String url) {
    return url.substring(url.indexOf("?t=") + 3);
  }

  @Test
  void enterLinkIsLocalOneTimeAndShortLived() {
    DesktopBridge b = bridge(true);
    String url = b.enterUrl();
    assertThat(url).startsWith("http://127.0.0.1:17380/api/desktop/enter?t=");
    String t = token(url);
    assertThat(b.consumeEnterToken(t)).isTrue();
    assertThat(b.consumeEnterToken(t)).as("второй раз").isFalse();

    String late = token(b.enterUrl());
    clock.advance(Duration.ofMinutes(3));
    assertThat(b.consumeEnterToken(late)).as("через три минуты").isFalse();
    assertThat(b.consumeEnterToken(null)).isFalse();
    assertThat(b.consumeEnterToken("выдуманный")).isFalse();
  }

  @Test
  void outsideDesktopNothingIsAccepted() {
    DesktopBridge b = bridge(false);
    assertThat(b.enabled()).isFalse();
    assertThat(b.consumeEnterToken(token(b.enterUrl()))).isFalse();
    AtomicInteger restarts = new AtomicInteger();
    b.onRestart(restarts::incrementAndGet);
    b.requestRestart();
    assertThat(restarts.get()).isZero();
    assertThat(b.restartRequested()).isFalse();
  }

  @Test
  void restartAndUpdateAreRemembered() {
    DesktopBridge b = bridge(true);
    AtomicInteger restarts = new AtomicInteger();
    b.onRestart(restarts::incrementAndGet);
    b.requestRestart();
    assertThat(b.restartRequested()).isTrue();
    assertThat(restarts.get()).isOne();

    assertThat(b.availableUpdate()).isNull();
    b.setAvailableUpdate(" 0.2.1 ");
    assertThat(b.availableUpdate()).isEqualTo("0.2.1");
    b.setAvailableUpdate("");
    assertThat(b.availableUpdate()).isNull();
  }

  @Test
  void shellIsAskedToCheckForUpdatesAtMostOncePerMinute() {
    DesktopBridge b = bridge(true);
    assertThat(b.requestCheck()).isTrue();
    assertThat(b.requestCheck()).as("сразу ещё раз").isFalse();
    clock.advance(Duration.ofSeconds(59));
    assertThat(b.requestCheck()).isFalse();
    clock.advance(Duration.ofSeconds(2));
    assertThat(b.requestCheck()).isTrue();
    assertThat(bridge(false).requestCheck()).as("не в приложении хоста").isFalse();
  }
}
