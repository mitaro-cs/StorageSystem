package app.groupbase.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Роли внутри группы по убыванию: староста, зам старосты, студент. */
public enum GroupRole {
  HEADMAN(3),
  DEPUTY(2),
  STUDENT(1);

  public final int rank;

  GroupRole(int rank) {
    this.rank = rank;
  }

  @JsonValue
  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  @JsonCreator
  public static GroupRole of(String id) {
    return id == null ? null : valueOf(id.toUpperCase(Locale.ROOT));
  }
}
