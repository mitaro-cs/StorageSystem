package app.groupbase;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Часы, которые тесты двигают вручную: истечение сессий, блокировки, шаги TOTP. */
public class TestClock extends Clock {

  private volatile Instant now;

  public TestClock(Instant start) {
    this.now = start;
  }

  public void advance(Duration d) {
    now = now.plus(d);
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  @Override
  public Instant instant() {
    return now;
  }
}
