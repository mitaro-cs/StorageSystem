package app.groupbase.store;

import app.groupbase.auth.InstanceRole;

public record User(
    long id,
    String username,
    String displayName,
    String passwordHash,
    boolean mustChangePassword,
    InstanceRole instanceRole,
    Status status,
    byte[] totpSecret,
    boolean totpEnabled,
    Long totpLastStep,
    String avatar,
    int failedLogins,
    Long lockedUntil,
    long createdAt) {

  public enum Status {
    PENDING,
    ACTIVE,
    BLOCKED,
    DELETED;

    public String id() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static Status of(String s) {
      return valueOf(s.toUpperCase(java.util.Locale.ROOT));
    }
  }

  public boolean isStaff() {
    return instanceRole != null;
  }
}
