package app.groupbase.store;

/** Автор контента. Для удалённого аккаунта имя пустое — фронт пишет «удалённый пользователь». */
public record Person(long id, String displayName, String avatar, boolean deleted) {

  public static Person of(Long id, String displayName, String avatar, String status) {
    if (id == null) {
      return new Person(0, "", null, true);
    }
    boolean deleted = "deleted".equals(status);
    return new Person(id, deleted ? "" : displayName, deleted ? null : avatar, deleted);
  }
}
