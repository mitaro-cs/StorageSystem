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
    @DefaultValue Http http) {

  /**
   * @param insecure разрешить работу по HTTP (режим «только локальная сеть»): cookie без Secure.
   */
  public record Http(
      @DefaultValue("8080") int port,
      @DefaultValue("127.0.0.1") String address,
      @DefaultValue("false") boolean insecure) {}

  public Path secretsDir() {
    return dataDir.resolve("secrets");
  }

  public Path databaseFile() {
    return dataDir.resolve("groupbase.db");
  }
}
