package app.groupbase.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.groupbase.web.ApiException;
import org.junit.jupiter.api.Test;

class NamesTest {

  @Test
  void transliteratesRussianNames() {
    assertThat(Names.suggestUsername("Иван Петров")).isEqualTo("ivan.petrov");
    assertThat(Names.suggestUsername("Щукина Юлия Сергеевна")).isEqualTo("shchukina.yuliya");
    assertThat(Names.suggestUsername("Ёж")).isEqualTo("ezh");
    assertThat(Names.suggestUsername("Я")).isEmpty();
    assertThat(Names.slug("БИН2509")).isEqualTo("bin2509");
  }

  @Test
  void validatesUsernames() {
    assertThat(Names.username("  Ivan.Petrov ")).isEqualTo("ivan.petrov");
    assertThatThrownBy(() -> Names.username("ив")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> Names.username(".dot")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> Names.username("deleted-5")).isInstanceOf(ApiException.class);
  }

  @Test
  void normalizesDisplayNames() {
    assertThat(Names.displayName("  Иван   Петров ")).isEqualTo("Иван Петров");
    assertThatThrownBy(() -> Names.displayName(" ")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> Names.displayName("a\u0000b")).isInstanceOf(ApiException.class);
  }
}
