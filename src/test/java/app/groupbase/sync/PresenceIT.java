package app.groupbase.sync;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** «Кто сейчас на сайте» (1.0.2). */
class PresenceIT extends IntegrationTest {

  private static List<Long> ids(TestUser u) {
    List<Long> out = new ArrayList<>();
    u.api().get("/api/presence").json().get("online").forEach(p -> out.add(p.get("id").asLong()));
    return out;
  }

  @Test
  void groupmatesSeeWhoIsOnlineStrangersDoNot() {
    long g = newGroup("Онлайн");
    TestUser anna = newUser(g, "student");
    TestUser boris = newUser(g, "student");
    TestUser stranger = newUser(newGroup("Чужие"), "student");

    // Без потока событий страница спрашивает номер состояния – и она на сайте.
    assertThat(anna.api().get("/api/live/seq").status()).isEqualTo(200);
    stranger.api().get("/api/live/seq");

    List<Long> seenByBoris = ids(boris);
    assertThat(seenByBoris).contains(anna.id(), boris.id()).doesNotContain(stranger.id());
    assertThat(ids(stranger)).doesNotContain(anna.id());
    assertThat(client().get("/api/presence").status()).isEqualTo(401);
  }
}
