package app.groupbase.hosts;

import app.groupbase.cli.Main;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Перед запуском сервера в приложении хоста: если сайт закрыли на другом компьютере или перенос
 * сюда уже заказан, свежие данные берутся из общей папки — до того, как сервер откроет базу.
 */
public final class HostSwitch {

  private HostSwitch() {}

  /**
   * @return порт, на котором сайт работал на прежнем компьютере (0 — неважно): адрес в CloudPub
   *     ведёт на этот порт
   */
  public static int prepare(Path dataDir, Consumer<String> say) {
    HostsConfig cfg;
    try {
      cfg = HostsConfig.load(dataDir);
    } catch (IOException e) {
      return 0;
    }
    Path dir = cfg.dir();
    if (dir == null || !Files.isDirectory(dir)) {
      return 0;
    }
    SiteFolder folder = new SiteFolder(dir);
    String name = cfg.pending();
    boolean claim = cfg.pendingClaim();
    if (name.isBlank()) {
      HostPlan.Plan plan;
      try {
        plan =
            HostPlan.decide(
                cfg.computerId(),
                cfg.epoch(),
                cfg.snapshot(),
                cfg.claim(),
                folder.host().orElse(null),
                folder.snapshotNames(),
                System.currentTimeMillis());
      } catch (IOException e) {
        return 0;
      }
      if (plan.kind() != HostPlan.Kind.TAKE || plan.snapshot().equals(cfg.failed(Main.version()))) {
        return 0;
      }
      name = plan.snapshot();
      claim = false;
    }
    try {
      say.accept("Берём свежие данные сайта из облачной папки…");
      SiteSnapshot.restore(dataDir, folder, name, say);
      cfg.setData(SiteFolder.epochOf(name), name);
      cfg.setPending(null, false);
      cfg.setClaim(claim);
      cfg.setError("");
      cfg.setFailed(null, "");
      cfg.save();
      return SiteSnapshot.port(folder, name);
    } catch (SiteSnapshot.Incomplete e) {
      // Облако ещё докачивает: сервер подождёт на странице ожидания и возьмёт данные сам.
      return 0;
    } catch (IOException | RuntimeException e) {
      cfg.setPending(null, false);
      cfg.setError("Не удалось взять данные с другого компьютера: " + e.getMessage());
      cfg.setFailed(name, Main.version());
      try {
        cfg.save();
      } catch (IOException ignored) {
        // Покажем ошибку хотя бы в журнале запуска.
      }
      say.accept(cfg.error());
      return 0;
    }
  }
}
