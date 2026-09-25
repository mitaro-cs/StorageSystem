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
    @DefaultValue Push push,
    @DefaultValue Backup backup,
    @DefaultValue Desktop desktop,
    @DefaultValue("true") boolean updateCheck) {

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
   * @param subject контакт администратора для служб push (mailto: с настоящим доменом или
   *     https://); по умолчанию — адрес сайта https, иначе страница проекта
   * @param allowedHosts домены служб push (совпадение домена или его поддомена)
   */
  public record Push(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("") String subject,
      @DefaultValue({
            "fcm.googleapis.com",
            "android.googleapis.com",
            "jmt17.google.com",
            "push.services.mozilla.com",
            "notify.windows.com",
            "push.apple.com"
          })
          java.util.List<String> allowedHosts) {}

  /**
   * Автобэкапы: раз в сутки после {@code at} (часовой пояс инстанса) в {@code dir} (по умолчанию
   * data/backups), хранятся {@code keep} последних.
   */
  public record Backup(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("03:30") String at,
      @DefaultValue("14") int keep,
      @DefaultValue("") String dir) {}

  /**
   * Сервер внутри приложения для компьютера (команда {@code desktop}): окно хоста ходит напрямую по
   * HTTP на 127.0.0.1, участники — по HTTPS через туннель. Поэтому Secure у cookie и HSTS решаются
   * для каждого запроса отдельно.
   */
  public record Desktop(@DefaultValue("false") boolean enabled) {}

  public Path backupsDir() {
    return backup.dir().isBlank() ? dataDir.resolve("backups") : Path.of(backup.dir());
  }

  public Path filesDir() {
    return dataDir.resolve("files");
  }

  public Path secretsDir() {
    return dataDir.resolve("secrets");
  }

  /** Программы, которые сервер скачивает сам (клиент туннеля). */
  public Path toolsDir() {
    return dataDir.resolve("tools");
  }

  public Path databaseFile() {
    return dataDir.resolve("groupbase.db");
  }
}
