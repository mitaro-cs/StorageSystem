package app.groupbase.accounts;

import app.groupbase.config.GroupbaseProperties;
import app.groupbase.store.SettingsStore;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Адрес сайта для участников — для ссылок-приглашений, QR-кодов и писем. На своём сервере это
 * {@code base-url} из настроек, в приложении хоста — адрес открытого доступа (туннеля или локальной
 * сети). Пусто — фронт берёт адрес из браузера.
 */
@Service
public class PublicUrl {

  public static final String SETTING = "access.url";

  private final String configured;
  private final SettingsStore settings;

  public PublicUrl(GroupbaseProperties props, SettingsStore settings) {
    this.configured = props.baseUrl().strip().replaceAll("/+$", "");
    this.settings = settings;
  }

  public Optional<String> get() {
    if (!configured.isEmpty()) {
      return Optional.of(configured);
    }
    return settings.get(SETTING).filter(s -> !s.isBlank());
  }

  /** Задан ли адрес в настройках сервера (тогда доступом из приложения не управляют). */
  public boolean fixed() {
    return !configured.isEmpty();
  }

  public void set(String url) {
    settings.set(SETTING, url == null ? "" : url);
  }
}
