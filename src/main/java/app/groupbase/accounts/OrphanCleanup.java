package app.groupbase.accounts;

import app.groupbase.store.SettingsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Один раз после обновления до 0.9.7: аккаунты, исключённые из всех групп раньше, удаляются – их
 * логины освобождаются (до 0.9.7 исключение оставляло аккаунт, и ник было не занять заново).
 */
@Component
class OrphanCleanup {

  static final String DONE = "accounts.orphans.cleaned";
  private static final Logger log = LoggerFactory.getLogger(OrphanCleanup.class);

  private final AccountService accounts;
  private final SettingsStore settings;

  OrphanCleanup(AccountService accounts, SettingsStore settings) {
    this.accounts = accounts;
    this.settings = settings;
  }

  @EventListener(ApplicationReadyEvent.class)
  void once() {
    if (settings.get(DONE).isPresent()) {
      return;
    }
    try {
      int n = accounts.deleteOrphans();
      settings.set(DONE, "1");
      if (n > 0) {
        log.info("Удалены аккаунты без групп (исключённые раньше): {}", n);
      }
    } catch (RuntimeException e) {
      log.warn("Не удалось убрать аккаунты без групп: {}", e.getMessage());
    }
  }
}
