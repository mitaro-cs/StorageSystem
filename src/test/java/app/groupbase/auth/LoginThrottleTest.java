package app.groupbase.auth;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.TestClock;
import app.groupbase.TestProps;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {

  private final TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
  private final LoginThrottle throttle = new LoginThrottle(clock, TestProps.defaults());

  @Test
  void fiveFailuresPerIpBlockUntilWindowPasses() {
    for (int i = 0; i < 5; i++) {
      assertThat(throttle.retryAfter("1.1.1.1", "user" + i)).isZero();
      throttle.recordFailure("1.1.1.1", "user" + i);
    }
    assertThat(throttle.retryAfter("1.1.1.1", "someone")).isPositive();
    assertThat(throttle.retryAfter("2.2.2.2", "someone")).isZero();
    clock.advance(Duration.ofMinutes(15).plusSeconds(1));
    assertThat(throttle.retryAfter("1.1.1.1", "someone")).isZero();
  }

  @Test
  void fiveFailuresPerAccountBlockFromAnyIp() {
    for (int i = 0; i < 5; i++) {
      throttle.recordFailure("10.0.0." + i, "Ivan");
    }
    assertThat(throttle.retryAfter("192.168.1.1", "ivan")).isPositive();
    throttle.recordSuccess("ivan");
    assertThat(throttle.retryAfter("192.168.1.1", "ivan")).isZero();
  }

  @Test
  void cleanupDropsStaleEntries() {
    throttle.recordFailure("1.1.1.1", "a");
    clock.advance(Duration.ofHours(1));
    throttle.cleanup();
    assertThat(throttle.retryAfter("1.1.1.1", "a")).isZero();
  }
}
