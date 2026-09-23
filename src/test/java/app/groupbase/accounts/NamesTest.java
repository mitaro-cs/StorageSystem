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
  void requiresFullName() {
    assertThat(Names.displayName("  Петров   Иван ")).isEqualTo("Петров Иван");
    assertThat(Names.displayName("Сарбашев Омар Русланович")).isEqualTo("Сарбашев Омар Русланович");
    assertThat(Names.displayName("Иванова-Петрова Анна-Мария"))
        .isEqualTo("Иванова-Петрова Анна-Мария");
    assertThat(Names.displayName("Алиев Рустам Ильхам оглы")).isEqualTo("Алиев Рустам Ильхам оглы");
    assertThat(Names.displayName("O'Brien Sean")).isEqualTo("O'Brien Sean");
    // Одного слова мало: нужны хотя бы фамилия и имя.
    assertThatThrownBy(() -> Names.displayName("Омар")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> Names.displayName(" ")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> Names.displayName(null)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> Names.displayName("Тест u123")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> Names.displayName("Петров Иван\u0000"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> Names.displayName("А Б В Г Д Е")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> Names.displayName("Петров " + "И".repeat(60)))
        .isInstanceOf(ApiException.class);
  }
}
