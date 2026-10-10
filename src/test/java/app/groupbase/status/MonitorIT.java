package app.groupbase.status;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.IntegrationTest;
import org.junit.jupiter.api.Test;

/** Мониторинг хоста – только администратору; запросы людей попадают в минуты. */
class MonitorIT extends IntegrationTest {

  @Test
  void adminSeesRequestsStudentDoesNot() {
    TestUser student = newUser(newGroup("Мониторинг"), "student");
    student.api().get("/api/me");
    assertThat(student.api().get("/api/admin/monitor").status()).isEqualTo(403);

    var hour = admin().get("/api/admin/monitor").json();
    assertThat(hour.get("points").size()).isEqualTo(60);
    assertThat(hour.get("step").asLong()).isEqualTo(60_000);
    int requests = 0;
    for (var p : hour.get("points")) {
      requests += p.get("requests").asInt();
    }
    assertThat(requests).isPositive();

    var day = admin().get("/api/admin/monitor?range=day").json();
    assertThat(day.get("points").size()).isEqualTo(96);
  }
}
