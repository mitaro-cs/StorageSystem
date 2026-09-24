package app.groupbase.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.IntegrationTest;
import app.groupbase.files.FileStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

class MaintenanceIT extends IntegrationTest {

  @Autowired ApplicationContext context;
  @Autowired FileStore files;

  @Test
  void cleanupRemovesExpiredLinksAndOrphanFiles() {
    Maintenance jobs = context.getAutowireCapableBeanFactory().createBean(Maintenance.class);
    long g = newGroup("Уборка");
    TestUser headman = newUser(g, "headman");
    var created =
        admin()
            .post(
                "/api/groups/" + g + "/accounts",
                Map.of("accounts", List.of(Map.of("displayName", "Забытов Забыт"))));
    String path = created.json().get(0).get("activationPath").asString();
    String token = path.substring(path.lastIndexOf('/') + 1);
    // Файл загрузили, но ни к чему не прикрепили.
    var file = headman.api().upload("черновик.txt", "черновик".getBytes());
    long fileId = file.json().get("id").asLong();
    assertThat(client().get("/api/auth/links/" + token).status()).isEqualTo(200);
    assertThat(files.find(fileId)).isPresent();

    clock.advance(Duration.ofDays(8));
    jobs.everyQuarterHour();
    jobs.everySixHours();

    assertThat(client().get("/api/auth/links/" + token).status()).isIn(404, 410);
    assertThat(files.find(fileId)).isEmpty();
  }
}
