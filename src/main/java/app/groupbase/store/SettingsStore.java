package app.groupbase.store;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Настройки инстанса (ключ → значение), меняются из интерфейса админа. */
@Repository
public class SettingsStore {

  private final JdbcClient db;

  public SettingsStore(JdbcClient db) {
    this.db = db;
  }

  public Optional<String> get(String key) {
    return db.sql("SELECT value FROM instance_settings WHERE key = ?")
        .param(key)
        .query(String.class)
        .optional();
  }

  public void set(String key, String value) {
    db.sql(
            "INSERT INTO instance_settings (key, value) VALUES (?, ?)"
                + " ON CONFLICT (key) DO UPDATE SET value = excluded.value")
        .params(key, value)
        .update();
  }

  public Map<String, String> all() {
    Map<String, String> m = new LinkedHashMap<>();
    db.sql("SELECT key, value FROM instance_settings ORDER BY key")
        .query(
            rs -> {
              m.put(rs.getString(1), rs.getString(2));
            });
    return m;
  }
}
