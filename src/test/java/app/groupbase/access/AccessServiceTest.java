package app.groupbase.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AccessServiceTest {

  @Test
  void subdomainRules() {
    assertThat(AccessService.normalize("  BIN2509-Mtusi ")).isEqualTo("bin2509-mtusi");
    for (String bad : new String[] {"", "ab", "-abc", "abc-", "a_b_c", "группа", "a".repeat(33)}) {
      assertThatThrownBy(() -> AccessService.normalize(bad))
          .as(bad)
          .isInstanceOf(AccessService.Problem.class);
    }
  }

  @Test
  void fxUrl() {
    assertThat(AccessService.fxUrl("grp")).isEqualTo("https://grp.fxtun.dev");
    assertThat(AccessService.fxUrl("")).isEmpty();
  }
}
