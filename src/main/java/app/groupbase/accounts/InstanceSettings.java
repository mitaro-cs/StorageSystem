package app.groupbase.accounts;

import app.groupbase.store.SettingsStore;
import org.springframework.stereotype.Service;

/** Типизированный доступ к настройкам инстанса. */
@Service
public class InstanceSettings {

  public enum Mode {
    SINGLE,
    MULTI;

    public String id() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static Mode of(String s) {
      return valueOf(s.toUpperCase(java.util.Locale.ROOT));
    }
  }

  public static final String MODE = "instance.mode";
  public static final String NAME = "instance.name";
  public static final String ACCOUNTS_DIRECT = "accounts.direct";
  public static final String ACCOUNTS_INVITES = "accounts.invites";

  private final SettingsStore store;

  public InstanceSettings(SettingsStore store) {
    this.store = store;
  }

  public Mode mode() {
    return Mode.of(store.get(MODE).orElse("single"));
  }

  public String name() {
    return store.get(NAME).orElse("");
  }

  public boolean directAccounts() {
    return Boolean.parseBoolean(store.get(ACCOUNTS_DIRECT).orElse("true"));
  }

  public boolean invites() {
    return Boolean.parseBoolean(store.get(ACCOUNTS_INVITES).orElse("true"));
  }

  public void set(String key, String value) {
    store.set(key, value);
  }
}
