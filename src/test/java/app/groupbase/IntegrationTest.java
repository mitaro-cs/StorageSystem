package app.groupbase;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Базовый класс интеграционных тестов: настоящий сервер на случайном порту и временная БД. */
@SpringBootTest(
    classes = GroupbaseApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

  protected static final Path DATA_DIR;

  static {
    try {
      DATA_DIR = Files.createTempDirectory("groupbase-test");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @LocalServerPort protected int port;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("groupbase.data-dir", DATA_DIR::toString);
  }

  protected String url(String path) {
    return "http://127.0.0.1:" + port + path;
  }
}
