package app.groupbase;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * У каждого пакета приложения есть свои тесты: новый пакет без них не пройдёт сборку. Считаются
 * юнит-тесты ({@code *Test}) и интеграционные ({@code *IT}) в пакете с тем же именем.
 */
class EveryPackageHasTestsTest {

  private static final Path MAIN = Path.of("src/main/java/app/groupbase");
  private static final Path TEST = Path.of("src/test/java/app/groupbase");

  @Test
  void everyPackageHasItsOwnTests() throws IOException {
    List<String> missing = new ArrayList<>();
    try (Stream<Path> pkgs = Files.list(MAIN)) {
      for (Path p : pkgs.filter(Files::isDirectory).sorted().toList()) {
        Path tests = TEST.resolve(p.getFileName().toString());
        boolean has;
        try (Stream<Path> files = Files.exists(tests) ? Files.walk(tests) : Stream.empty()) {
          has =
              files.anyMatch(
                  f -> f.toString().endsWith("Test.java") || f.toString().endsWith("IT.java"));
        }
        if (!has) {
          missing.add(p.getFileName().toString());
        }
      }
    }
    assertThat(missing).as("пакеты без тестов").isEmpty();
  }
}
