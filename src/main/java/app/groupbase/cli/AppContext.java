package app.groupbase.cli;

import app.groupbase.GroupbaseApplication;
import app.groupbase.config.ConfigLoader;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Запуск контекста Spring для CLI-команд: с веб-сервером ({@code serve}) или без него. */
final class AppContext {

  private AppContext() {}

  static ConfigurableApplicationContext start(Path config, boolean web, String... args)
      throws IOException {
    return new SpringApplicationBuilder(GroupbaseApplication.class)
        .environment(ConfigLoader.environment(ConfigLoader.resolve(config)))
        .web(web ? WebApplicationType.SERVLET : WebApplicationType.NONE)
        .profiles(web ? "serve" : "cli")
        .bannerMode(Banner.Mode.OFF)
        .logStartupInfo(web)
        .run(args);
  }
}
