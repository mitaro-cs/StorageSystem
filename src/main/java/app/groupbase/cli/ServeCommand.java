package app.groupbase.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "serve", description = "Запустить веб-сервер.", mixinStandardHelpOptions = true)
public class ServeCommand implements Callable<Integer> {

  @Option(
      names = {"-c", "--config"},
      description = "Путь к groupbase.toml (по умолчанию $GROUPBASE_CONFIG или ./groupbase.toml).")
  Path config;

  @Override
  public Integer call() throws Exception {
    ConfigurableApplicationContext ctx = AppContext.start(config, true);
    CountDownLatch closed = new CountDownLatch(1);
    ctx.addApplicationListener(
        (org.springframework.context.ApplicationListener<ContextClosedEvent>)
            e -> closed.countDown());
    // Ждём SIGTERM/SIGINT: Spring закроет контекст (graceful shutdown), затем выходим.
    closed.await();
    return 0;
  }
}
