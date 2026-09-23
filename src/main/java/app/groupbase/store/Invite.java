package app.groupbase.store;

import app.groupbase.auth.GroupRole;

public record Invite(
    long id,
    long groupId,
    GroupRole role,
    Integer maxUses,
    int uses,
    long expiresAt,
    String note,
    Long createdBy,
    long createdAt,
    Long revokedAt) {

  public boolean usable(long now) {
    return revokedAt == null && expiresAt > now && (maxUses == null || uses < maxUses);
  }
}
