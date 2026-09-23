package app.groupbase.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

  @Test
  void flattensTomlTables(@TempDir Path dir) throws Exception {
    Path f = dir.resolve("groupbase.toml");
    Files.writeString(
        f,
        """
        data_dir = "/srv/gb"
        [http]
        port = 9000
        [backup]
        keep_daily = 7
        targets = ["a", "b"]
        """);
    Map<String, Object> m = ConfigLoader.read(f);
    assertThat(m)
        .containsEntry("groupbase.data_dir", "/srv/gb")
        .containsEntry("groupbase.http.port", "9000")
        .containsEntry("groupbase.backup.keep_daily", "7")
        .containsEntry("groupbase.backup.targets[1]", "b");
  }

  @Test
  void environmentOverridesToml(@TempDir Path dir) throws Exception {
    Path f = dir.resolve("groupbase.toml");
    Files.writeString(f, "base_url = \"https://toml\"\n");
    var env = ConfigLoader.environment(f);
    assertThat(env.getProperty("groupbase.base_url")).isEqualTo("https://toml");
    assertThat(
            env.getPropertySources().precedenceOf(env.getPropertySources().get("groupbase.toml")))
        .isGreaterThan(
            env.getPropertySources()
                .precedenceOf(
                    env.getPropertySources()
                        .get(
                            org.springframework.core.env.StandardEnvironment
                                .SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)));
  }
}
