package app.groupbase.status;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.TestClock;
import app.groupbase.sync.LiveUpdates;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Мониторинг хоста (1.0.2): перцентили, доступность, история на диске. */
class MonitorTest {

  private final TestClock clock = new TestClock(Instant.parse("2026-10-10T10:00:30Z"));

  private Monitor monitor(Path dir) {
    return new Monitor(clock, dir.resolve("monitor.json"), new LiveUpdates(null), Optional::empty);
  }

  @Test
  void percentilesComeFromTheHistogram() {
    int[] hist = new int[Monitor.BOUNDS.length + 1];
    assertThat(Monitor.percentile(hist, 0.5)).isNull();
    hist[Monitor.bucket(3)] = 90; // ≤5 мс
    hist[Monitor.bucket(300)] = 10; // ≤320 мс
    assertThat(Monitor.percentile(hist, 0.5)).isEqualTo(5);
    assertThat(Monitor.percentile(hist, 0.95)).isEqualTo(320);
  }

  @Test
  void requestsErrorsAndUptimeByMinute(@TempDir Path dir) {
    Monitor m = monitor(dir);
    m.request(12, 200);
    m.request(15, 200);
    m.request(900, 503);
    m.probed(true, 140);
    var v = m.view(60, 1);
    assertThat(v.points()).hasSize(60);
    var last = v.points().getLast();
    assertThat(last.requests()).isEqualTo(3);
    assertThat(last.errors()).isEqualTo(1);
    assertThat(last.rtt()).isEqualTo(140);
    assertThat(last.reach()).isEqualTo(1.0);
    assertThat(v.now().errorsHour()).isEqualTo(1);
    assertThat(v.uptimeHour()).isEqualTo(1.0);

    // Минута, когда адрес сайта не отвечал, – простой; пустая минута (сервер выключен) – тоже.
    clock.advance(Duration.ofMinutes(1));
    m.request(10, 200);
    m.probed(false, 10_000);
    clock.advance(Duration.ofMinutes(2));
    m.request(10, 200);
    v = m.view(60, 1);
    assertThat(v.uptimeHour()).isEqualTo(2.0 / 4);
    assertThat(v.now().reachable()).isFalse();
  }

  @Test
  void historySurvivesRestart(@TempDir Path dir) {
    Monitor first = monitor(dir);
    first.request(20, 200);
    first.save();
    Monitor second = monitor(dir);
    second.load();
    assertThat(second.view(60, 1).points().getLast().requests()).isEqualTo(1);
    // Сутки спустя старые минуты не показываются.
    clock.advance(Duration.ofDays(1).plusMinutes(1));
    Monitor later = monitor(dir);
    later.load();
    assertThat(later.view(24 * 60, 15).uptimeDay()).isNull();
  }
}
