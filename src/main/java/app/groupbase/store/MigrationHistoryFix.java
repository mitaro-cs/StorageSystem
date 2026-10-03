package app.groupbase.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

/**
 * Починка журнала миграций после ошибки 0.7.0: в нём изменили уже выпущенную в 0.6.0 миграцию V22
 * (переименовали в {@code V22__tips_and_looks.sql} и добавили колонки оформления). Базы 0.6 не
 * открывались новой версией (проверка Flyway — GB-206), а базы, созданные 0.7.0, не откроются
 * исправленной. Здесь запись о V22 из 0.7.0 приводится к выпущенной V22; колонки оформления у таких
 * баз уже есть, и V25 их пропускает.
 */
@Component
public class MigrationHistoryFix implements Callback {

  /** Контрольная сумма Flyway у V22__tips.sql из 0.6.0 — файл с тех пор не меняется. */
  static final int V22_CHECKSUM = 335260119;

  @Override
  public boolean supports(Event event, Context context) {
    return event == Event.BEFORE_VALIDATE || event == Event.BEFORE_MIGRATE;
  }

  @Override
  public boolean canHandleInTransaction(Event event, Context context) {
    return true;
  }

  @Override
  public void handle(Event event, Context context) {
    try {
      fix(context.getConnection());
    } catch (SQLException e) {
      throw new IllegalStateException("Не удалось поправить журнал миграций", e);
    }
  }

  static void fix(Connection c) throws SQLException {
    try (Statement st = c.createStatement()) {
      boolean history;
      try (var rs =
          st.executeQuery(
              "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'flyway_schema_history'")) {
        history = rs.next();
      }
      if (history) {
        st.executeUpdate(
            "UPDATE flyway_schema_history SET description = 'tips', script = 'V22__tips.sql',"
                + " checksum = "
                + V22_CHECKSUM
                + " WHERE version = '22' AND script = 'V22__tips_and_looks.sql'");
      }
    }
  }

  @Override
  public String getCallbackName() {
    return "groupbase-v22-fix";
  }
}
