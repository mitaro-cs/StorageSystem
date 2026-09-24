package app.groupbase.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.groupbase.web.ApiException;
import org.junit.jupiter.api.Test;

class TelegramTest {

  @Test
  void acceptsWhatPeopleCopy() {
    assertThat(Telegram.normalize("https://t.me/bin2509_chat", "url"))
        .isEqualTo("https://t.me/bin2509_chat");
    assertThat(Telegram.normalize("  t.me/bin2509_chat/  ", "url"))
        .isEqualTo("https://t.me/bin2509_chat");
    assertThat(Telegram.normalize("@bin2509_chat", "url")).isEqualTo("https://t.me/bin2509_chat");
    assertThat(Telegram.normalize("https://t.me/+AbCdEf12345", "url"))
        .isEqualTo("https://t.me/+AbCdEf12345");
    assertThat(Telegram.normalize("http://telegram.me/joinchat/XyZ123", "url"))
        .isEqualTo("https://t.me/joinchat/XyZ123");
    assertThat(Telegram.normalize("tg://resolve?domain=mtuci_news", "url"))
        .isEqualTo("https://t.me/mtuci_news");
    assertThat(Telegram.normalize("tg://join?invite=AbCd123", "url"))
        .isEqualTo("https://t.me/+AbCd123");
    assertThat(Telegram.normalize("https://t.me/mtuci_news?utm_source=x", "url"))
        .isEqualTo("https://t.me/mtuci_news");
    assertThat(Telegram.normalize("", "url")).isEmpty();
    assertThat(Telegram.normalize(null, "url")).isEmpty();
  }

  @Test
  void rejectsOtherSites() {
    for (String bad :
        new String[] {
          "https://vk.com/club1",
          "https://t.me.evil.ru/x",
          "javascript:alert(1)",
          "https://t.me/",
          "https://t.me/<script>",
          "@a"
        }) {
      assertThatThrownBy(() -> Telegram.normalize(bad, "url"))
          .as(bad)
          .isInstanceOf(ApiException.class);
    }
  }
}
