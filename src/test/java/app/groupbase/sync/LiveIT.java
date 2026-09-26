package app.groupbase.sync;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Живые обновления: открытая страница сразу узнаёт о новом комментарии и уведомлении. */
class LiveIT extends IntegrationTest {

  /** Ждёт событие с этим именем (остальные пропускает), не дольше 5 секунд. */
  private static boolean next(BlockingQueue<String> events, String name)
      throws InterruptedException {
    long until = System.currentTimeMillis() + 5000;
    while (System.currentTimeMillis() < until) {
      String e = events.poll(100, MILLISECONDS);
      if (name.equals(e)) {
        return true;
      }
    }
    return false;
  }

  @Test
  void pageLearnsAboutNewCommentsAndNotificationsRightAway() throws Exception {
    long g = newGroup("Живые");
    TestUser student = newUser(g, "student");
    TestUser other = newUser(g, "student");
    long post =
        admin()
            .post("/api/news", Map.of("title", "Живая новость", "groupIds", List.of(g)))
            .json()
            .get("id")
            .asLong();

    BlockingQueue<String> events = new LinkedBlockingQueue<>();
    Thread reader =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (Stream<String> lines = student.api().stream("/api/live")) {
                    lines
                        .filter(l -> l.startsWith("event:"))
                        .forEach(l -> events.add(l.substring("event:".length()).strip()));
                  } catch (Exception e) {
                    // Поток закрыт — тест закончился.
                  }
                });
    try {
      assertThat(next(events, "hello")).as("поток доходит сразу").isTrue();

      other.api().post("/api/news/" + post + "/comments", Map.of("body", "Сразу видно"));
      assertThat(next(events, "change")).as("новый комментарий").isTrue();

      // Новость группы — уведомление этому студенту: колокольчик обновляется сразу.
      admin().post("/api/news", Map.of("title", "Ещё новость", "groupIds", List.of(g)));
      assertThat(next(events, "bell")).as("колокольчик").isTrue();
    } finally {
      reader.interrupt();
    }
    // Без входа поток не отдаётся.
    assertThat(client().get("/api/live").status()).isEqualTo(401);
  }
}
