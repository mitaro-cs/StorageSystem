package app.groupbase.backup;

import app.groupbase.store.UserStore;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ежедневные копии. Проверка раз в 20 минут: компьютер хоста может быть выключен в 03:30. */
@Component
@Profile("serve")
class BackupJob {

  private static final Logger log = LoggerFactory.getLogger(BackupJob.class);

  private final BackupService backups;
  private final UserStore users;
  private final Clock clock;

  BackupJob(BackupService backups, UserStore users, Clock clock) {
    this.backups = backups;
    this.users = users;
    this.clock = clock;
  }

  @Scheduled(initialDelayString = "PT3M", fixedDelayString = "PT20M")
  void tick() {
    // Пока инстанс не настроен, копировать нечего.
    if (users.count() == 0 || !backups.due(clock.millis())) {
      return;
    }
    try {
      backups.scheduled();
      log.info("Резервная копия создана");
    } catch (RuntimeException e) {
      log.warn("Резервная копия не создана: {}", e.getMessage());
    }
  }
}
