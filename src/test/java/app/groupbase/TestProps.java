package app.groupbase;

import app.groupbase.config.GroupbaseProperties;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Настройки для юнит-тестов: значения по умолчанию плюс переопределения, как в groupbase.toml. */
public final class TestProps {

  private TestProps() {}

  public static GroupbaseProperties of(Map<String, String> overrides) {
    return new Binder(new MapConfigurationPropertySource(overrides))
        .bindOrCreate("groupbase", GroupbaseProperties.class);
  }

  public static GroupbaseProperties defaults() {
    return of(Map.of());
  }
}
