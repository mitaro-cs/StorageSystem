package app.groupbase.jobs;

import app.groupbase.audit.AuditService;
import app.groupbase.auth.LoginService;
import app.groupbase.auth.LoginThrottle;
import app.groupbase.auth.SessionService;
import app.groupbase.files.FileStore;
import app.groupbase.notify.NotificationStore;
import app.groupbase.store.UserTokenStore;
import app.groupbase.sync.SyncService;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Регулярная уборка: истёкшие сессии и ссылки, счётчики попыток входа, IP в журнале аудита,
 * уведомления старше 90 дней.
 */
@Configuration
@EnableScheduling
@Profile("serve")
class Maintenance {

  private static final Logger log = LoggerFactory.getLogger(Maintenance.class);

  private final SessionService sessions;
  private final UserTokenStore tokens;
  private final LoginThrottle throttle;
  private final LoginService login;
  private final AuditService audit;
  private final FileStore files;
  private final NotificationStore notifications;
  private final SyncService sync;
  private final Clock clock;

  Maintenance(
      SessionService sessions,
      UserTokenStore tokens,
      LoginThrottle throttle,
      LoginService login,
      AuditService audit,
      FileStore files,
      NotificationStore notifications,
      SyncService sync,
      Clock clock) {
    this.sync = sync;
    this.files = files;
    this.notifications = notifications;
    this.sessions = sessions;
    this.tokens = tokens;
    this.throttle = throttle;
    this.login = login;
    this.audit = audit;
    this.clock = clock;
  }

  @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT15M")
  void everyQuarterHour() {
    throttle.cleanup();
    login.cleanupTickets();
  }

  @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT6H")
  void everySixHours() {
    int s = sessions.purgeExpired();
    int t = tokens.deleteExpired(clock.millis());
    int ips = audit.forgetOldIps();
    int orphans = files.deleteOrphans(clock.millis() - java.time.Duration.ofDays(1).toMillis());
    int old =
        notifications.deleteOlderThan(clock.millis() - java.time.Duration.ofDays(90).toMillis());
    sync.purge();
    log.info(
        "Уборка: сессий {}, ссылок {}, IP в аудите {}, файлов {}, уведомлений {}",
        s,
        t,
        ips,
        orphans,
        old);
  }
}
