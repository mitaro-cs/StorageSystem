package app.groupbase.sync;

import app.groupbase.auth.Actor;
import app.groupbase.store.People;
import app.groupbase.store.Person;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * «Кто сейчас на сайте» (1.0.2): люди с открытой страницей, но только из групп, где состоит и сам
 * спрашивающий (администратор видит всех). Имена и аватары – как в списке участников.
 */
@Service
public class Presence {

  private final LiveUpdates live;
  private final JdbcClient db;
  private final People people;

  public Presence(LiveUpdates live, JdbcClient db, People people) {
    this.live = live;
    this.db = db;
    this.people = people;
  }

  public List<Person> online(Actor actor) {
    Set<Long> ids = live.online();
    ids.add(actor.id());
    List<Long> visible =
        actor.isAdmin()
            ? List.copyOf(ids)
            : db.sql(
                    """
                    SELECT DISTINCT m2.user_id FROM memberships m1
                    JOIN memberships m2 ON m2.group_id = m1.group_id
                    WHERE m1.user_id = :me AND m2.user_id IN (:ids)
                    """)
                .param("me", actor.id())
                .param("ids", ids)
                .query(Long.class)
                .list();
    var loaded = people.load(visible);
    return visible.stream()
        .map(id -> people.get(loaded, id))
        .filter(p -> p != null && !p.deleted())
        .sorted(Comparator.comparing(Person::displayName))
        .toList();
  }
}
