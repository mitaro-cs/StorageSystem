package app.groupbase.cli;

import app.groupbase.config.ConfigLoader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import picocli.CommandLine.Option;

/** Общие параметры команд обслуживания: где groupbase.toml и где данные. */
final class Target {

  @Option(
      names = {"-c", "--config"},
      paramLabel = "ФАЙЛ",
      description = "groupbase.toml (по умолчанию $GROUPBASE_CONFIG или ./groupbase.toml).")
  Path config;

  @Option(
      names = {"-d", "--data"},
      paramLabel = "КАТАЛОГ",
      description =
          "Каталог данных, важнее groupbase.toml. Данные приложения хоста:%n"
              + "  macOS — ~/Library/Application Support/app.groupbase%n"
              + "  Windows — %%APPDATA%%\\app.groupbase")
  Path data;

  StandardEnvironment environment() throws IOException {
    StandardEnvironment env = ConfigLoader.environment(ConfigLoader.resolve(config));
    if (data != null) {
      env.getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "cli", Map.of("groupbase.data-dir", data.toAbsolutePath().toString())));
    }
    return env;
  }

  Path dataDir() throws IOException {
    return dataDir(environment());
  }

  static Path dataDir(StandardEnvironment env) {
    return Path.of(env.getProperty("groupbase.data-dir", "./data")).toAbsolutePath().normalize();
  }

  /** База и сервисы без веб-сервера и фоновых задач. */
  ConfigurableApplicationContext open() throws IOException {
    return AppContext.cli(environment());
  }
}
