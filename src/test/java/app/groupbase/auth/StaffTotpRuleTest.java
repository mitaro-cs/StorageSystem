package app.groupbase.auth;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.store.User;
import org.junit.jupiter.api.Test;

/** Обязательная 2FA — только у администратора: модератор входит на любом устройстве паролем. */
class StaffTotpRuleTest {

  private static User user(InstanceRole role) {
    return new User(
        1, "u", "У", "h", false, role, User.Status.ACTIVE, null, false, null, 0, null, 0, null, 0);
  }

  @Test
  void onlyAdminMustUseTotp() {
    assertThat(SessionService.totpRequired(user(InstanceRole.ADMIN))).isTrue();
    assertThat(SessionService.totpRequired(user(InstanceRole.MODERATOR))).isFalse();
    assertThat(SessionService.totpRequired(user(null))).isFalse();
  }
}
