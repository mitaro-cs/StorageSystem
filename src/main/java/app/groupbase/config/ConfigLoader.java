package app.groupbase.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * Читает {@code groupbase.toml} и превращает его в плоский набор свойств {@code groupbase.*}.
 *
 * <p>Приоритет: аргументы CLI → переменные окружения ({@code GROUPBASE_DATA_DIR} и т.п.) → TOML →
 * значения по умолчанию из {@code application.properties}.
 */
public final class ConfigLoader {

  public static final String ENV_CONFIG = "GROUPBASE_CONFIG";
  private static final List<Path> DEFAULT_LOCATIONS =
      List.of(Path.of("groupbase.toml"), Path.of("/data/groupbase.toml"));

  private ConfigLoader() {}

  /** Путь к конфигу: явный, из окружения или первый существующий из стандартных. */
  public static Path resolve(Path explicit) {
    if (explicit != null) {
      return explicit;
    }
    String env = System.getenv(ENV_CONFIG);
    if (env != null && !env.isBlank()) {
      return Path.of(env);
    }
    return DEFAULT_LOCATIONS.stream().filter(Files::isRegularFile).findFirst().orElse(null);
  }

  /** Окружение Spring с TOML-источником между переменными окружения и application.properties. */
  public static StandardEnvironment environment(Path configFile) throws IOException {
    StandardEnvironment env = new StandardEnvironment();
    Map<String, Object> props = new LinkedHashMap<>();
    if (configFile != null) {
      if (!Files.isRegularFile(configFile)) {
        throw new IOException("Файл конфигурации не найден: " + configFile);
      }
      props.putAll(read(configFile));
      props.put("groupbase.config-file", configFile.toAbsolutePath().toString());
    }
    env.getPropertySources().addLast(new MapPropertySource("groupbase.toml", props));
    return env;
  }

  /** Разворачивает TOML в ключи вида {@code groupbase.backup.keep_daily}. */
  public static Map<String, Object> read(Path file) throws IOException {
    Map<?, ?> tree = new TomlMapper().readValue(file.toFile(), Map.class);
    Map<String, Object> flat = new LinkedHashMap<>();
    flatten("groupbase", tree, flat);
    return flat;
  }

  private static void flatten(String prefix, Map<?, ?> tree, Map<String, Object> out) {
    for (Map.Entry<?, ?> e : tree.entrySet()) {
      String key = prefix + "." + e.getKey();
      if (e.getValue() instanceof Map<?, ?> nested) {
        flatten(key, nested, out);
      } else if (e.getValue() instanceof List<?> list) {
        for (int i = 0; i < list.size(); i++) {
          out.put(key + "[" + i + "]", String.valueOf(list.get(i)));
        }
      } else {
        out.put(key, String.valueOf(e.getValue()));
      }
    }
  }
}
