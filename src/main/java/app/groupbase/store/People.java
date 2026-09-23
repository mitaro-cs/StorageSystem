package app.groupbase.store;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Пакетная загрузка авторов для списков. */
@Repository
public class People {

  private final JdbcClient db;

  public People(JdbcClient db) {
    this.db = db;
  }

  public Map<Long, Person> load(Collection<Long> ids) {
    Map<Long, Person> out = new HashMap<>();
    if (ids.isEmpty()) {
      return out;
    }
    db.sql("SELECT id, display_name, avatar, status FROM users WHERE id IN (:ids)")
        .param("ids", ids)
        .query(
            rs -> {
              long id = rs.getLong(1);
              out.put(id, Person.of(id, rs.getString(2), rs.getString(3), rs.getString(4)));
            });
    return out;
  }

  public Person get(Map<Long, Person> loaded, Long id) {
    return id == null
        ? Person.of(null, null, null, null)
        : loaded.getOrDefault(id, Person.of(null, null, null, null));
  }
}
