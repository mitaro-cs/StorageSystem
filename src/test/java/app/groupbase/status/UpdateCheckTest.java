package app.groupbase.status;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UpdateCheckTest {

  @Test
  void comparesVersions() {
    assertThat(UpdateCheck.newer("0.3.0", "0.2.9")).isTrue();
    assertThat(UpdateCheck.newer("1.0", "0.9.9")).isTrue();
    assertThat(UpdateCheck.newer("0.2.0", "0.2.0")).isFalse();
    assertThat(UpdateCheck.newer("0.2.0", "0.10.0")).isFalse();
    assertThat(UpdateCheck.newer("v0.2.1", "0.2.0-SNAPSHOT")).isTrue();
  }
}
