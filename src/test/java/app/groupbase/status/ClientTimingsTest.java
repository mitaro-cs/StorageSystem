package app.groupbase.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientTimingsTest {

  /** Часы, которые можно двигать. */
  static final class Moving extends Clock {
    long now = Instant.parse("2026-10-12T09:00:00Z").toEpochMilli();

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(now);
    }
  }

  @Test
  void groupsByDeviceNetworkAndPath(@TempDir Path dir) {
    Moving clock = new Moving();
    ClientTimings t = new ClientTimings(clock, dir.resolve("timings.json"));
    assertThat(
            t.report(
                1,
                new ClientTimings.Report(
                    "phone", "cellular", "tunnel", List.of(300, 500, 700), 2400)))
        .isTrue();
    // Чаще раза в минуту от человека – отбрасывается.
    assertThat(
            t.report(
                1, new ClientTimings.Report("phone", "cellular", "tunnel", List.of(9000), null)))
        .isFalse();
    assertThat(
            t.report(
                2, new ClientTimings.Report("phone", "cellular", "tunnel", List.of(400), null)))
        .isTrue();
    // Неизвестные значения не плодят строк.
    assertThat(t.report(3, new ClientTimings.Report("toaster", "5g", "mars", List.of(20, 30), 600)))
        .isTrue();

    var rows = t.view();
    assertThat(rows).hasSize(2);
    var phone = rows.get(0);
    assertThat(phone.device()).isEqualTo("phone");
    assertThat(phone.people()).isEqualTo(2);
    assertThat(phone.samples()).isEqualTo(4);
    assertThat(phone.apiP50()).isEqualTo(640);
    assertThat(phone.loadP50()).isEqualTo(3000);
    var other = rows.get(1);
    assertThat(other.device()).isEqualTo("computer");
    assertThat(other.net()).isEqualTo("unknown");
    assertThat(other.via()).isEqualTo("tunnel");
    assertThat(other.apiP95()).isEqualTo(40);
  }

  @Test
  void keepsADayAndSurvivesRestart(@TempDir Path dir) {
    Moving clock = new Moving();
    Path file = dir.resolve("timings.json");
    ClientTimings t = new ClientTimings(clock, file);
    t.report(1, new ClientTimings.Report("computer", "wifi", "local", List.of(15), null));
    t.save();
    assertThat(new ClientTimings(clock, file).view()).hasSize(1);

    // Через сутки старое уходит.
    clock.now += 25 * ClientTimings.HOUR;
    assertThat(t.view()).isEmpty();
    assertThat(new ClientTimings(clock, file).view()).isEmpty();
  }
}
