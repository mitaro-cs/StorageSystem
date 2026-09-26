package app.groupbase.hosts;

import app.groupbase.hosts.SiteFolder.Host;
import java.util.List;

/**
 * Что делать этому компьютеру, глядя на общую папку. Без побочных действий — одно и то же решение
 * принимается при запуске (до сервера) и потом каждые несколько секунд.
 *
 * <p>Поколение (epoch) — номер «владения» сайтом: растёт каждый раз, когда сайт переходит на другой
 * компьютер. Данные из поколения постарше всегда уступают данным из более нового.
 */
final class HostPlan {

  enum Kind {
    /** Сайт работает здесь. */
    HOST,
    /** Хост закрыл сайт — взять его данные и работать здесь, не спрашивая. */
    TAKE,
    /** Сайт работает на другом компьютере: можно попросить передать его сюда. */
    LIVE,
    /** Другой компьютер давно не на связи: можно запустить здесь с его последним снимком. */
    SILENT,
    /** Этот компьютер передал сайт другому и ждёт, пока тот его примет. */
    HANDED,
    /** На другом компьютере перенос между компьютерами выключили. */
    DETACHED
  }

  /**
   * @param other запись о хосте (может быть и этого компьютера — для HANDED)
   * @param snapshot какой снимок брать (TAKE, SILENT)
   * @param quietFor сколько другой компьютер не на связи
   */
  record Plan(Kind kind, Host other, String snapshot, long quietFor) {}

  /** Хост пишет о себе раз в минуту; три минуты тишины — «не на связи». */
  static final long FRESH_MS = 3 * 60_000;

  /** Перезапуск и обновление занимают до пары минут; дольше — компьютер, видимо, выключили. */
  static final long RESTART_MS = 5 * 60_000;

  private HostPlan() {}

  static Plan decide(
      String me,
      long localEpoch,
      String localSnapshot,
      boolean claim,
      Host h,
      List<String> snapshots,
      long now) {
    String newest = snapshots.isEmpty() ? null : snapshots.getLast();
    long newestEpoch = newest == null ? -1 : SiteFolder.epochOf(newest);
    if (claim) {
      return new Plan(Kind.HOST, h, null, 0);
    }
    if (h == null) {
      // Записи о хосте нет (удалили или ещё не доехала), а снимки из нового поколения есть.
      if (newestEpoch > localEpoch && !newest.equals(localSnapshot)) {
        return new Plan(Kind.SILENT, null, newest, now - SiteFolder.timeOf(newest));
      }
      return new Plan(Kind.HOST, null, null, 0);
    }
    if (me.equals(h.computerId())) {
      if (newestEpoch > h.epoch()) {
        // Сайт уже запускали на другом компьютере, а его запись о себе ещё не доехала.
        return new Plan(Kind.SILENT, null, newest, now - SiteFolder.timeOf(newest));
      }
      if (SiteFolder.STOPPED.equals(h.state()) && h.to() != null && !h.to().equals(me)) {
        return new Plan(Kind.HANDED, h, null, now - h.heartbeat());
      }
      return new Plan(Kind.HOST, h, null, 0);
    }
    if (h.epoch() < localEpoch) {
      // Наше поколение новее — это старая запись того компьютера, он уступит сам.
      return new Plan(Kind.HOST, h, null, 0);
    }
    long quiet = Math.max(0, now - h.heartbeat());
    String state = h.state() == null ? SiteFolder.RUNNING : h.state();
    return switch (state) {
      case SiteFolder.STOPPED -> {
        String snap = h.snapshot() == null ? null : h.snapshot().name();
        if (snap == null || snap.equals(localSnapshot) || SiteFolder.epochOf(snap) < localEpoch) {
          yield new Plan(Kind.HOST, h, null, quiet);
        }
        yield new Plan(Kind.TAKE, h, snap, quiet);
      }
      case SiteFolder.DETACHED -> new Plan(Kind.DETACHED, h, newest, quiet);
      case SiteFolder.RESTARTING ->
          quiet < RESTART_MS
              ? new Plan(Kind.LIVE, h, null, quiet)
              : new Plan(Kind.SILENT, h, newest, quiet);
      default ->
          quiet < FRESH_MS
              ? new Plan(Kind.LIVE, h, null, quiet)
              : new Plan(Kind.SILENT, h, newest, quiet);
    };
  }
}
