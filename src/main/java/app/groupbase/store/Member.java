package app.groupbase.store;

import app.groupbase.auth.GroupRole;

/** Участник группы вместе с его аккаунтом. */
public record Member(
    long userId,
    String username,
    String displayName,
    String avatar,
    User.Status status,
    GroupRole role,
    long joinedAt) {

  /** Копия без логина — для тех, кому его видеть не положено. */
  public Member withoutUsername() {
    return new Member(userId, null, displayName, avatar, status, role, joinedAt);
  }
}
