package app.groupbase.status;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.IntegrationTest;
import java.util.List;
import java.util.Map;
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

  @Test
  void browsersReportTheirSpeedAndOnlyTheAdminSeesTheTable() {
    TestUser student = newUser(newGroup("Скорость"), "student");
    var r =
        student
            .api()
            .post(
                "/api/monitor/timings",
                Map.of(
                    "device",
                    "phone",
                    "net",
                    "cellular",
                    "via",
                    "tunnel",
                    "api",
                    List.of(120, 260, 900),
                    "load",
                    1800));
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    assertThat(r.json().get("accepted").asBoolean()).isTrue();
    assertThat(client().post("/api/monitor/timings", Map.of()).status()).isIn(401, 403);
    assertThat(student.api().get("/api/admin/monitor/clients").status()).isEqualTo(403);

    var rows = admin().get("/api/admin/monitor/clients").json();
    boolean phone = false;
    for (var row : rows) {
      if (row.get("device").asString().equals("phone")
          && row.get("net").asString().equals("cellular")) {
        phone = true;
        assertThat(row.get("people").asInt()).isPositive();
        assertThat(row.get("apiP95").asInt()).isEqualTo(1280);
        assertThat(row.get("loadP50").asInt()).isEqualTo(2000);
      }
    }
    assertThat(phone).isTrue();
  }
}
