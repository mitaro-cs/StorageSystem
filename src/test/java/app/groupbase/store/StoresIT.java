package app.groupbase.store;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.IntegrationTest;
import app.groupbase.auth.GroupRole;
import app.groupbase.auth.InstanceRole;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class StoresIT extends IntegrationTest {

  @Autowired SettingsStore settings;
  @Autowired GroupStore groups;
  @Autowired UserStore users;

  @Test
  void settingsAreStoredAndOverwritten() {
    String key = "test." + uniq();
    assertThat(settings.get(key)).isEmpty();
    settings.set(key, "первое");
    settings.set(key, "второе");
    assertThat(settings.get(key)).hasValue("второе");
  }

  @Test
  void membersComeHeadmanFirstThenDeputiesThenAlphabet() {
    long g = newGroup("Порядок");
    TestUser student = newUser(g, "student");
    TestUser headman = newUser(g, "headman");
    TestUser deputy = newUser(g, "deputy");
    users.setInstanceRole(deputy.id(), InstanceRole.MODERATOR);

    List<Member> ms = groups.members(g);
    assertThat(ms)
        .extracting(Member::userId)
        .containsExactly(headman.id(), deputy.id(), student.id());
    assertThat(ms)
        .extracting(Member::role)
        .containsExactly(GroupRole.HEADMAN, GroupRole.DEPUTY, GroupRole.STUDENT);
    assertThat(ms.get(1).instanceRole()).isEqualTo(InstanceRole.MODERATOR);
    assertThat(ms.get(0).instanceRole()).isNull();
    // Без логина — для тех, кому его видеть не положено; роль при этом остаётся.
    Member hidden = ms.get(1).withoutUsername();
    assertThat(hidden.username()).isNull();
    assertThat(hidden.instanceRole()).isEqualTo(InstanceRole.MODERATOR);
    users.setInstanceRole(deputy.id(), null);
  }
}
