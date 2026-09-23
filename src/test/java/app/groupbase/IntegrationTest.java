package app.groupbase;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.accounts.SetupService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Базовый класс интеграционных тестов: настоящий сервер на случайном порту, временная БД на
 * контекст, управляемые часы. Инстанс настраивается один раз (admin / {@link #ADMIN_PASSWORD}).
 */
@SpringBootTest(
    classes = GroupbaseApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "groupbase.http.insecure=true",
      "groupbase.auth.require-staff-totp=false",
      "groupbase.uploads.max-file-mb=1"
    })
@Import(IntegrationTest.ClockOverride.class)
public abstract class IntegrationTest {

  public static final String ADMIN = "admin";
  public static final String ADMIN_PASSWORD = "admin-password-1";
  public static final String PASSWORD = "student-password-1";
  private static final AtomicInteger SEQ = new AtomicInteger();

  @TestConfiguration
  static class ClockOverride {
    @Bean
    @Primary
    TestClock testClock() {
      return new TestClock(Instant.parse("2026-09-01T09:00:00Z"));
    }
  }

  @LocalServerPort protected int port;
  @Autowired protected app.groupbase.config.GroupbaseProperties props;
  @Autowired protected TestClock clock;
  @Autowired protected SetupService setup;
  @Autowired protected Clock springClock;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    try {
      // Новый каталог на каждый контекст Spring: у контекстов с разными настройками своя БД.
      String dir = Files.createTempDirectory("groupbase-test").toString();
      r.add("groupbase.data-dir", () -> dir);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  protected String url(String path) {
    return "http://127.0.0.1:" + port + path;
  }

  protected ApiClient client() {
    return new ApiClient(port);
  }

  /** Уникальный суффикс для имён, чтобы тесты не мешали друг другу в общей БД. */
  protected static String uniq() {
    return Integer.toString(SEQ.incrementAndGet(), 36)
        + Long.toString(System.nanoTime() % 46656, 36);
  }

  /** Вошедший администратор. При первом вызове выполняет первичную настройку. */
  protected ApiClient admin() {
    synchronized (IntegrationTest.class) {
      if (setup.needed()) {
        ApiClient c = client();
        var r =
            c.post(
                "/api/setup",
                Map.of(
                    "code",
                    setup.setupCode(),
                    "mode",
                    "multi",
                    "group",
                    Map.of("name", "БИН2509", "university", "МТУСИ", "course", 1),
                    "username",
                    ADMIN,
                    "displayName",
                    "Админов Админ Админович",
                    "password",
                    ADMIN_PASSWORD));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
      }
    }
    return login(ADMIN, ADMIN_PASSWORD);
  }

  protected ApiClient login(String username, String password) {
    ApiClient c = client();
    var r = c.post("/api/auth/login", Map.of("username", username, "password", password));
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    return c;
  }

  protected long newGroup(String name) {
    var r = admin().post("/api/groups", Map.of("name", name + " " + uniq(), "university", "МТУСИ"));
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    return r.json().get("id").asLong();
  }

  public record TestUser(long id, String username, ApiClient api) {}

  /** Новый активированный пользователь с ролью в группе, уже вошедший. */
  protected TestUser newUser(long groupId, String role) {
    String username = "u" + uniq();
    var r =
        admin()
            .post(
                "/api/groups/" + groupId + "/accounts",
                Map.of(
                    "accounts",
                    List.of(Map.of("username", username, "displayName", "Тестов Тест")),
                    "role",
                    role,
                    "delivery",
                    "LINK"));
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    long id = r.json().get(0).get("userId").asLong();
    String path = r.json().get(0).get("activationPath").asString();
    ApiClient c = client();
    var a =
        c.post(
            "/api/auth/links/" + path.substring("/activate/".length()),
            Map.of("password", PASSWORD));
    assertThat(a.status()).as(a.body()).isEqualTo(200);
    return new TestUser(id, username, c);
  }
}
