package app.groupbase.auth;

/**
 * Пользователь текущего запроса.
 *
 * @param restriction не null — сессия ограничена: разрешены только смена пароля или настройка 2FA
 * @param local окно приложения хоста на его компьютере (см. {@link SessionService#create(long,
 *     boolean)})
 */
public record Actor(
    long id,
    String username,
    String displayName,
    InstanceRole instanceRole,
    boolean totpEnabled,
    Restriction restriction,
    byte[] sessionHash,
    boolean local) {

  public enum Restriction {
    PASSWORD_CHANGE_REQUIRED,
    TOTP_SETUP_REQUIRED;

    public String id() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  public boolean isAdmin() {
    return instanceRole == InstanceRole.ADMIN;
  }
}
