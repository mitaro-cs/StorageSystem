package app.groupbase.notify;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Какие push-уведомления человек хочет получать. В приложении видно всё независимо от них. */
@Repository
public class NotificationPrefs {

  /**
   * @param news all — все новости, urgent — только срочные, none — никакие
   * @param digestAt время утренней сводки в минутах от полуночи (часовой пояс инстанса)
   */
  public record Prefs(
      boolean homework,
      String news,
      boolean materials,
      boolean reminders,
      boolean digest,
      int digestAt) {

    public static final Prefs DEFAULT = new Prefs(true, "all", false, true, false, 8 * 60);

    public boolean wantsNews(boolean urgent) {
      return "all".equals(news) || (urgent && "urgent".equals(news));
    }
  }

  private final JdbcClient db;

  public NotificationPrefs(JdbcClient db) {
    this.db = db;
  }

  public Prefs get(long userId) {
    return forUsers(List.of(userId)).getOrDefault(userId, Prefs.DEFAULT);
  }

  public Map<Long, Prefs> forUsers(Collection<Long> users) {
    Map<Long, Prefs> out = new HashMap<>();
    if (users.isEmpty()) {
      return out;
    }
    db.sql(
            "SELECT user_id, homework, news, materials, reminders, digest, digest_at"
                + " FROM notification_prefs WHERE user_id IN (:u)")
        .param("u", List.copyOf(users))
        .query(
            rs -> {
              out.put(
                  rs.getLong("user_id"),
                  new Prefs(
                      rs.getInt("homework") == 1,
                      rs.getString("news"),
                      rs.getInt("materials") == 1,
                      rs.getInt("reminders") == 1,
                      rs.getInt("digest") == 1,
                      rs.getInt("digest_at")));
            });
    for (Long u : users) {
      out.putIfAbsent(u, Prefs.DEFAULT);
    }
    return out;
  }

  public void save(long userId, Prefs p) {
    db.sql(
            """
            INSERT INTO notification_prefs (user_id, homework, news, materials, reminders, digest,
                                            digest_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id) DO UPDATE SET homework = excluded.homework,
              news = excluded.news, materials = excluded.materials,
              reminders = excluded.reminders, digest = excluded.digest,
              digest_at = excluded.digest_at
            """)
        .params(
            userId,
            p.homework() ? 1 : 0,
            p.news(),
            p.materials() ? 1 : 0,
            p.reminders() ? 1 : 0,
            p.digest() ? 1 : 0,
            p.digestAt())
        .update();
  }
}
