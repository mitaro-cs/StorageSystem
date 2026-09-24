package app.groupbase.audit;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.IntegrationTest;
import app.groupbase.store.AuditStore;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class AuditIT extends IntegrationTest {

  @Autowired AuditService audit;
  @Autowired AuditStore store;

  private static JsonNode find(JsonNode list, String action, long targetId) {
    for (JsonNode e : list) {
      if (e.get("action").asString().equals(action) && e.get("targetId").asLong() == targetId) {
        return e;
      }
    }
    return null;
  }

  @Test
  void actionsAreLoggedAndVisibleOnlyToTheirGroup() {
    long g = newGroup("Журнал");
    long other = newGroup("Чужой журнал");
    TestUser headman = newUser(g, "headman");
    TestUser strangerHeadman = newUser(other, "headman");
    var inv = headman.api().post("/api/groups/" + g + "/invites", Map.of("ttlHours", 1));
    long inviteId = inv.json().get("invite").get("id").asLong();

    JsonNode own = headman.api().get("/api/groups/" + g + "/audit").json();
    JsonNode entry = find(own, "invite.create", inviteId);
    assertThat(entry).isNotNull();
    assertThat(entry.get("actorId").asLong()).isEqualTo(headman.id());
    assertThat(entry.get("groupId").asLong()).isEqualTo(g);

    // Староста другой группы чужой журнал не видит, администратор — видит всё.
    assertThat(strangerHeadman.api().get("/api/groups/" + g + "/audit").status()).isIn(403, 404);
    assertThat(find(admin().get("/api/admin/audit").json(), "invite.create", inviteId)).isNotNull();
  }

  @Test
  void ipIsKeptForAMonthAndPasswordsNever() {
    long g = newGroup("IP");
    TestUser u = newUser(g, "headman");
    u.api().post("/api/groups/" + g + "/invites", Map.of("ttlHours", 1, "note", "для чата"));
    var entries = audit.list(java.util.List.of(g), null, 50);
    assertThat(entries).isNotEmpty();
    assertThat(entries).allSatisfy(e -> assertThat(e.ip()).isEqualTo("127.0.0.1"));
    assertThat(entries)
        .allSatisfy(e -> assertThat(String.valueOf(e.details())).doesNotContain(PASSWORD));
    store.forgetIps(clock.millis() + 1);
    assertThat(audit.list(java.util.List.of(g), null, 50))
        .allSatisfy(e -> assertThat(e.ip()).isNull());
  }
}
