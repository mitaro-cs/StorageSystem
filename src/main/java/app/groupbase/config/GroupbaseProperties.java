package app.groupbase.config;

import java.nio.file.Path;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Все настройки из {@code groupbase.toml} и переменных окружения {@code GROUPBASE_*}. Полный список
 * с описанием — в docs/config.md.
 */
@ConfigurationProperties("groupbase")
public record GroupbaseProperties(
    @DefaultValue("./data") Path dataDir,
    @DefaultValue("") String baseUrl,
    @DefaultValue("Europe/Moscow") ZoneId timezone,
    @DefaultValue Http http,
    @DefaultValue Auth auth,
    @DefaultValue Uploads uploads,
    @DefaultValue Push push) {

  /**
   * @param insecure разрешить работу по HTTP (режим «только локальная сеть»): cookie без Secure.
   */
  public record Http(
      @DefaultValue("8080") int port,
      @DefaultValue("127.0.0.1") String address,
      @DefaultValue("false") boolean insecure) {}

  /**
   * @param sessionDays срок жизни сессии; продлевается при активности
   * @param requireStaffTotp обязательная 2FA для admin и moderator
   * @param activationDays срок действия ссылок активации и сброса пароля
   */
  public record Auth(
      @DefaultValue("30") int sessionDays,
      @DefaultValue("true") boolean requireStaffTotp,
      @DefaultValue("7") int activationDays,
      @DefaultValue("5") int maxLoginAttempts,
      @DefaultValue("15") int loginWindowMinutes) {}

  /**
   * @param maxFileMb лимит на один загружаемый файл
   */
  public record Uploads(@DefaultValue("50") int maxFileMb) {
    public long maxBytes() {
      return maxFileMb * 1024L * 1024L;
    }
  }

  /**
   * Web Push. Уведомления уходят только в службы браузеров из списка — защита от запросов сервера
   * на произвольные адреса. Содержимое зашифровано для устройства (RFC 8291), службы его не видят.
   *
   * @param subject контакт администратора для служб push (mailto: или https://); по умолчанию —
   *     base-url
   * @param allowedHosts домены служб push (совпадение домена или его поддомена)
   */
  public record Push(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("") String subject,
      @DefaultValue({
            "fcm.googleapis.com",
            "android.googleapis.com",
            "push.services.mozilla.com",
            "notify.windows.com",
            "push.apple.com"
          })
          java.util.List<String> allowedHosts) {}

  public Path filesDir() {
    return dataDir.resolve("files");
  }

  public Path secretsDir() {
    return dataDir.resolve("secrets");
  }

  public Path databaseFile() {
    return dataDir.resolve("groupbase.db");
  }
}
