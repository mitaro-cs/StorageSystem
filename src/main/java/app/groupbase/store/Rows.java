package app.groupbase.store;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Мелочи для ручных RowMapper: nullable-числа и булевы флаги SQLite. */
public final class Rows {

  private Rows() {}

  public static Long longOrNull(ResultSet rs, String col) throws SQLException {
    long v = rs.getLong(col);
    return rs.wasNull() ? null : v;
  }

  public static Integer intOrNull(ResultSet rs, String col) throws SQLException {
    int v = rs.getInt(col);
    return rs.wasNull() ? null : v;
  }

  public static boolean bool(ResultSet rs, String col) throws SQLException {
    return rs.getInt(col) != 0;
  }

  /** Экранирует % и _ для LIKE ... ESCAPE '\'. */
  public static String likeEscape(String s) {
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
