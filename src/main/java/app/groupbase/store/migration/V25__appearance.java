package app.groupbase.store.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Оформление общее для всех устройств человека (0.7): тема, дизайн, цвет, приглушение и размытие
 * фона, значок — JSON (users.appearance, пусто — ещё не выбирал), своя картинка фона — WebP в
 * каталоге аватаров (users.background — её идентификатор).
 *
 * <p>Миграция на Java, а не SQL: в 0.7.0 эти колонки по ошибке добавлялись в уже выпущенную V22, и
 * у таких баз они есть — тогда добавлять нечего (см. {@code MigrationHistoryFix}).
 */
public class V25__appearance extends BaseJavaMigration {

  @Override
  public void migrate(Context context) throws SQLException {
    Connection c = context.getConnection();
    try (Statement st = c.createStatement()) {
      if (!hasColumn(c, "appearance")) {
        st.execute("ALTER TABLE users ADD COLUMN appearance TEXT NOT NULL DEFAULT ''");
      }
      if (!hasColumn(c, "background")) {
        st.execute("ALTER TABLE users ADD COLUMN background TEXT");
      }
    }
  }

  static boolean hasColumn(Connection c, String column) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT name FROM pragma_table_info('users')")) {
      while (rs.next()) {
        if (column.equals(rs.getString(1))) {
          return true;
        }
      }
    }
    return false;
  }
}
