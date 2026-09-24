package app.groupbase.store;

import app.groupbase.auth.GroupRole;
import app.groupbase.auth.InstanceRole;

/** Участник группы вместе с его аккаунтом. */
public record Member(
    long userId,
    String username,
    String displayName,
    String avatar,
    User.Status status,
    GroupRole role,
    InstanceRole instanceRole,
    long joinedAt) {

  /** Копия без логина — для тех, кому его видеть не положено. */
  public Member withoutUsername() {
    return new Member(userId, null, displayName, avatar, status, role, instanceRole, joinedAt);
  }
}
