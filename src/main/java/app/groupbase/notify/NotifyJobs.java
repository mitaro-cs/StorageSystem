package app.groupbase.notify;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;

/** Каждые 5 минут: напоминания о сроках и утренние сводки. */
@Configuration
@Profile("serve")
class NotifyJobs {

  private static final Logger log = LoggerFactory.getLogger(NotifyJobs.class);

  private final Reminders reminders;
  private final Clock clock;

  NotifyJobs(Reminders reminders, Clock clock) {
    this.reminders = reminders;
    this.clock = clock;
  }

  @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT5M")
  void run() {
    long now = clock.millis();
    int r = reminders.dayBefore(now);
    int d = reminders.digest(now);
    if (r + d > 0) {
      log.info("Напоминаний: {}, сводок: {}", r, d);
    }
  }
}
