package app.groupbase.hosts;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.hosts.HostPlan.Kind;
import app.groupbase.hosts.SiteFolder.Host;
import app.groupbase.hosts.SiteFolder.Snap;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Кто хост: решение по записи в общей папке, снимкам и данным этого компьютера. */
class HostPlanTest {

  static final String ME = "me-computer";
  static final String PC = "pc-computer";
  static final long NOW = 1_790_000_000_000L;
  static final long MIN = 60_000;

  static Host host(String id, long epoch, String state, long heartbeat, String snap, String to) {
    return new Host(
        1,
        id,
        id.equals(ME) ? "Ноутбук" : "ПК дома",
        epoch,
        state,
        heartbeat,
        heartbeat - 60 * MIN,
        snap == null ? null : new Snap(snap, SiteFolder.timeOf(snap), 100, "ПК дома"),
        to,
        to == null ? null : "Ноутбук",
        "0.4.9");
  }

  static String snap(long epoch, long at) {
    return SiteFolder.snapshotName(epoch, at, "pc");
  }

  static HostPlan.Plan decide(long epoch, String local, Host h, String... snapshots) {
    return HostPlan.decide(ME, epoch, local, false, h, List.of(snapshots), NOW);
  }

  @Test
  void firstComputerIsHost() {
    assertThat(decide(0, "", null).kind()).isEqualTo(Kind.HOST);
  }

  @Test
  void ownRecordMeansHostAgain() {
    String s = snap(3, NOW - 10 * MIN);
    assertThat(decide(3, s, host(ME, 3, "stopped", NOW - 30 * MIN, s, null), s).kind())
        .isEqualTo(Kind.HOST);
    assertThat(decide(3, s, host(ME, 3, "running", NOW - 30 * MIN, s, null), s).kind())
        .isEqualTo(Kind.HOST);
  }

  @Test
  void otherRunningAndFreshIsLive() {
    String s = snap(4, NOW - 5 * MIN);
    var p = decide(3, snap(3, NOW - 90 * MIN), host(PC, 4, "running", NOW - MIN, s, null), s);
    assertThat(p.kind()).isEqualTo(Kind.LIVE);
    assertThat(p.other().computer()).isEqualTo("ПК дома");
  }

  @Test
  void otherQuietForMinutesIsSilentWithNewestSnapshot() {
    String older = snap(4, NOW - 30 * MIN);
    String newer = snap(4, NOW - 12 * MIN);
    var p = decide(3, "", host(PC, 4, "running", NOW - 11 * MIN, older, null), older, newer);
    assertThat(p.kind()).isEqualTo(Kind.SILENT);
    assertThat(p.snapshot()).isEqualTo(newer);
    assertThat(p.quietFor()).isEqualTo(11 * MIN);
  }

  @Test
  void otherClosedWithNewerDataIsTakenRightAway() {
    String s = snap(4, NOW - 2 * MIN);
    var p = decide(3, snap(3, NOW - 90 * MIN), host(PC, 4, "stopped", NOW - 2 * MIN, s, null), s);
    assertThat(p.kind()).isEqualTo(Kind.TAKE);
    assertThat(p.snapshot()).isEqualTo(s);
  }

  @Test
  void otherClosedButWeAlreadyHaveItsDataMeansHost() {
    String s = snap(4, NOW - 2 * MIN);
    assertThat(decide(4, s, host(PC, 4, "stopped", NOW - 2 * MIN, s, null), s).kind())
        .isEqualTo(Kind.HOST);
  }

  @Test
  void olderGenerationRecordYieldsToUs() {
    // Мы уже взяли сайт (поколение 5), а запись ПК (4) доехала позже — ПК уступит сам.
    String s = snap(4, NOW - 2 * MIN);
    assertThat(decide(5, snap(5, NOW - MIN), host(PC, 4, "running", NOW, s, null), s).kind())
        .isEqualTo(Kind.HOST);
  }

  @Test
  void restartingHostIsWaitedForAFewMinutes() {
    String s = snap(4, NOW - 2 * MIN);
    assertThat(decide(3, "", host(PC, 4, "restarting", NOW - 2 * MIN, s, null), s).kind())
        .isEqualTo(Kind.LIVE);
    assertThat(decide(3, "", host(PC, 4, "restarting", NOW - 6 * MIN, s, null), s).kind())
        .isEqualTo(Kind.SILENT);
  }

  @Test
  void handedOverWaitsForTheOtherComputer() {
    String s = snap(4, NOW - MIN);
    var p = decide(4, s, host(ME, 4, "stopped", NOW - MIN, s, PC), s);
    assertThat(p.kind()).isEqualTo(Kind.HANDED);
    assertThat(p.other().toName()).isEqualTo("Ноутбук");
  }

  @Test
  void newerSnapshotThanOwnRecordMeansSomeoneElseTookTheSite() {
    // Наша запись ещё висит, а снимок нового поколения уже доехал: сайт запускали без нас.
    String ours = snap(4, NOW - 30 * MIN);
    String theirs = snap(5, NOW - 5 * MIN);
    var p = decide(4, ours, host(ME, 4, "running", NOW - 20 * MIN, ours, null), ours, theirs);
    assertThat(p.kind()).isEqualTo(Kind.SILENT);
    assertThat(p.snapshot()).isEqualTo(theirs);
  }

  @Test
  void claimAfterTakeoverMakesHost() {
    String s = snap(4, NOW - 20 * MIN);
    assertThat(
            HostPlan.decide(
                    ME,
                    4,
                    s,
                    true,
                    host(PC, 4, "running", NOW - 20 * MIN, s, null),
                    List.of(s),
                    NOW)
                .kind())
        .isEqualTo(Kind.HOST);
  }

  @Test
  void detachedElsewhere() {
    String s = snap(4, NOW - 20 * MIN);
    assertThat(decide(3, "", host(PC, 4, "detached", NOW - 20 * MIN, s, null), s).kind())
        .isEqualTo(Kind.DETACHED);
  }
}
