package app.groupbase.cli;

import app.groupbase.GroupbaseApplication;
import app.groupbase.backup.PendingRestore;
import app.groupbase.config.ConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** Запуск контекста Spring для CLI-команд: с веб-сервером ({@code serve}) или без него. */
final class AppContext {

  private AppContext() {}

  static ConfigurableApplicationContext start(Path config, boolean web, String... args)
      throws IOException {
    StandardEnvironment env = ConfigLoader.environment(ConfigLoader.resolve(config));
    if (web) {
      applyPendingRestore(env, System.out::println);
    }
    return builder(env, web ? new String[] {"serve"} : new String[] {"cli"})
        .logStartupInfo(web)
        .run(args);
  }

  /**
   * Сервер для приложения хоста: каталог данных задаёт оболочка, groupbase.toml — необязательный,
   * рядом с данными. Настройки оболочки (порт, адрес) важнее файла.
   */
  static ConfigurableApplicationContext desktop(
      Path dataDir, Map<String, Object> overrides, Consumer<String> say) throws IOException {
    Path toml = dataDir.resolve("groupbase.toml");
    StandardEnvironment env = ConfigLoader.environment(Files.isRegularFile(toml) ? toml : null);
    env.getPropertySources().addFirst(new MapPropertySource("desktop", overrides));
    applyPendingRestore(env, say);
    return builder(env, new String[] {"serve", "desktop"}).logStartupInfo(false).run();
  }

  /** Для команд обслуживания: база и сервисы, без веб-сервера, фоновых задач и блокировки. */
  static ConfigurableApplicationContext cli(StandardEnvironment env) {
    return builder(env, new String[] {"cli"}).logStartupInfo(false).run();
  }

  private static SpringApplicationBuilder builder(StandardEnvironment env, String[] profiles) {
    return new SpringApplicationBuilder(GroupbaseApplication.class)
        .environment(env)
        .web(profiles[0].equals("serve") ? WebApplicationType.SERVLET : WebApplicationType.NONE)
        .profiles(profiles)
        .bannerMode(Banner.Mode.OFF);
  }

  /** Начало сообщения о неудачном восстановлении — по нему оболочка показывает код GB-208. */
  static final String RESTORE_FAILED = "Не удалось восстановить из копии";

  /** Восстановление из копии, заказанное из интерфейса, — до того как сервер откроет базу. */
  private static void applyPendingRestore(StandardEnvironment env, Consumer<String> say)
      throws IOException {
    Path data = Path.of(env.getProperty("groupbase.data-dir", "./data"));
    try {
      PendingRestore.apply(data, say);
    } catch (IOException e) {
      throw new IOException(RESTORE_FAILED + ": " + e.getMessage(), e);
    }
  }
}
