package app.groupbase.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Роли уровня инстанса. Хранятся в {@code users.instance_role}. */
public enum InstanceRole {
  ADMIN(5),
  MODERATOR(4);

  public final int rank;

  InstanceRole(int rank) {
    this.rank = rank;
  }

  @JsonValue
  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  @JsonCreator
  public static InstanceRole of(String id) {
    return id == null ? null : valueOf(id.toUpperCase(Locale.ROOT));
  }
}
