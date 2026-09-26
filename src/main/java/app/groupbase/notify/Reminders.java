package app.groupbase.notify;

import app.groupbase.config.GroupbaseProperties;
import app.groupbase.notify.PushSender.Message;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Напоминания о сроке за сутки и утренняя сводка. Запускаются по расписанию (см. NotifyJobs);
 * {@code now} передаётся явно, чтобы их можно было проверять в тестах.
 */
@Service
public class Reminders {

  static final long HOUR = 3_600_000L;
  static final long DAY = 24 * HOUR;

  /** Сводка уходит не позже, чем через столько минут после выбранного времени. */
  static final int DIGEST_WINDOW_MIN = 180;

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

  private final JdbcClient db;
  private final Notifier notifier;
  private final PushSender push;
  private final PushSubscriptions subs;
  private final ZoneId zone;

  public Reminders(
      JdbcClient db,
      Notifier notifier,
      PushSender push,
      PushSubscriptions subs,
      GroupbaseProperties props) {
    this.db = db;
    this.notifier = notifier;
    this.push = push;
    this.subs = subs;
    this.zone = props.timezone();
  }

  private record Due(
      long homeworkId, String title, long dueAt, String subject, String kind, long userId) {}

  /**
   * Напоминание за сутки до срока тем, кто ещё не отметил задание выполненным. Задания, выложенные
   * меньше чем за сутки до срока, не напоминаются: хватит уведомления о новом задании.
   */
  public int dayBefore(long now) {
    List<Due> rows =
        db.sql(
                """
                SELECT h.id, h.title, h.due_at, h.kind, s.name AS subject, m.user_id
                FROM homework h
                JOIN subjects s ON s.id = h.subject_id
                JOIN homework_targets t ON t.homework_id = h.id
                JOIN memberships m ON m.group_id = t.group_id
                JOIN users u ON u.id = m.user_id AND u.status = 'active'
                LEFT JOIN notification_prefs np ON np.user_id = m.user_id
                WHERE h.hidden = 0 AND h.due_at > :now AND h.due_at <= :now + :day
                  AND h.created_at <= h.due_at - :day
                  AND ifnull(np.reminders, 1) = 1
                  AND NOT EXISTS (SELECT 1 FROM homework_done d
                                  WHERE d.user_id = m.user_id AND d.homework_id = h.id)
                  AND NOT EXISTS (SELECT 1 FROM homework_reminders r
                                  WHERE r.user_id = m.user_id AND r.homework_id = h.id)
                  AND NOT EXISTS (SELECT 1 FROM subject_hidden sh
                                  WHERE sh.user_id = m.user_id AND sh.subject_id = h.subject_id)
                GROUP BY h.id, m.user_id
                """)
            .param("now", now)
            .param("day", DAY)
            .query(
                (rs, i) ->
                    new Due(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getLong("due_at"),
                        rs.getString("subject"),
                        rs.getString("kind"),
                        rs.getLong("user_id")))
            .list();
    Map<Long, List<Due>> byHomework = new LinkedHashMap<>();
    for (Due d : rows) {
      byHomework.computeIfAbsent(d.homeworkId(), k -> new ArrayList<>()).add(d);
    }
    int sent = 0;
    for (List<Due> list : byHomework.values()) {
      Due first = list.getFirst();
      List<Long> users = new ArrayList<>();
      for (Due d : list) {
        int inserted =
            db.sql(
                    "INSERT OR IGNORE INTO homework_reminders (user_id, homework_id, sent_at)"
                        + " VALUES (?, ?, ?)")
                .params(d.userId(), d.homeworkId(), now)
                .update();
        if (inserted == 1) {
          users.add(d.userId());
        }
      }
      notifier.deliver(
          users,
          new Message(
              "reminder",
              soon(first.kind()) + " · " + first.subject(),
              first.title()
                  + (isExam(first.kind()) ? " — " : " — сдать ")
                  + when(first.dueAt(), now),
              "/homework/" + first.homeworkId(),
              true),
          p -> true);
      sent += users.size();
    }
    return sent;
  }

  static boolean isExam(String kind) {
    return "credit".equals(kind) || "exam".equals(kind);
  }

  /** Заголовок напоминания: у зачёта и экзамена — своё. */
  static String soon(String kind) {
    return switch (kind) {
      case "credit" -> "Скоро зачёт";
      case "exam" -> "Скоро экзамен";
      case "test" -> "Скоро контрольная";
      default -> "Скоро срок";
    };
  }

  /** «сегодня в 23:59», «завтра в 10:00». */
  String when(long due, long now) {
    ZonedDateTime d = Instant.ofEpochMilli(due).atZone(zone);
    LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
    String day = d.toLocalDate().equals(today) ? "сегодня" : "завтра";
    return day + " в " + TIME.format(d);
  }

  private record Candidate(long userId, int digestAt, String last) {}

  /** Утренняя сводка push-уведомлением: что сдать сегодня и завтра, что просрочено. */
  public int digest(long now) {
    if (!push.enabled()) {
      return 0;
    }
    ZonedDateTime local = Instant.ofEpochMilli(now).atZone(zone);
    String today = local.toLocalDate().toString();
    int minute = local.getHour() * 60 + local.getMinute();
    long startOfTomorrow =
        local.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
    List<Candidate> candidates =
        db.sql(
                """
                SELECT np.user_id, np.digest_at, np.digest_last FROM notification_prefs np
                JOIN users u ON u.id = np.user_id AND u.status = 'active'
                WHERE np.digest = 1
                  AND EXISTS (SELECT 1 FROM push_subscriptions ps WHERE ps.user_id = np.user_id)
                """)
            .query(
                (rs, i) ->
                    new Candidate(
                        rs.getLong("user_id"), rs.getInt("digest_at"), rs.getString("digest_last")))
            .list();
    int sent = 0;
    for (Candidate c : candidates) {
      if (today.equals(c.last())
          || minute < c.digestAt()
          || minute > c.digestAt() + DIGEST_WINDOW_MIN) {
        continue;
      }
      db.sql("UPDATE notification_prefs SET digest_last = ? WHERE user_id = ?")
          .params(today, c.userId())
          .update();
      int[] n = counts(c.userId(), now, startOfTomorrow);
      Message m = digestMessage(n[0], n[1], n[2]);
      if (m != null) {
        push.sendAsync(subs.forUsers(List.of(c.userId())), m);
        sent++;
      }
    }
    return sent;
  }

  /** Сегодня (до конца дня), завтра, просрочено за две недели — без выполненных. */
  private int[] counts(long userId, long now, long startOfTomorrow) {
    return db.sql(
            """
            SELECT
              ifnull(sum(h.due_at >= :now AND h.due_at < :tomorrow), 0) AS today,
              ifnull(sum(h.due_at >= :tomorrow AND h.due_at < :after), 0) AS next,
              ifnull(sum(h.due_at < :now), 0) AS overdue
            FROM homework h
            WHERE h.hidden = 0 AND h.due_at >= :since AND h.due_at < :after
              AND EXISTS (SELECT 1 FROM homework_targets t JOIN memberships m ON m.group_id = t.group_id
                          WHERE t.homework_id = h.id AND m.user_id = :u)
              AND NOT EXISTS (SELECT 1 FROM homework_done d
                              WHERE d.homework_id = h.id AND d.user_id = :u)
              AND NOT EXISTS (SELECT 1 FROM subject_hidden sh
                              WHERE sh.user_id = :u AND sh.subject_id = h.subject_id)
            """)
        .param("now", now)
        .param("tomorrow", startOfTomorrow)
        .param("after", startOfTomorrow + DAY)
        .param("since", now - 14 * DAY)
        .param("u", userId)
        .query((rs, i) -> new int[] {rs.getInt("today"), rs.getInt("next"), rs.getInt("overdue")})
        .single();
  }

  static Message digestMessage(int today, int tomorrow, int overdue) {
    if (today + tomorrow + overdue == 0) {
      return null;
    }
    String title;
    List<String> rest = new ArrayList<>();
    if (today > 0) {
      title = "Сегодня сдать " + today + " " + tasks(today);
      if (tomorrow > 0) {
        rest.add("завтра — " + tomorrow);
      }
    } else if (tomorrow > 0) {
      title = "Завтра сдать " + tomorrow + " " + tasks(tomorrow);
    } else {
      title = "Есть просроченные задания";
    }
    if (overdue > 0) {
      rest.add("просрочено — " + overdue);
    }
    String body = rest.isEmpty() ? "Хорошего дня!" : String.join(", ", rest);
    return new Message("digest", title, capitalize(body), "/", false);
  }

  static String tasks(int n) {
    int m100 = n % 100;
    int m10 = n % 10;
    if (m100 >= 11 && m100 <= 14) {
      return "заданий";
    }
    return m10 == 1 ? "задание" : m10 >= 2 && m10 <= 4 ? "задания" : "заданий";
  }

  private static String capitalize(String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}
