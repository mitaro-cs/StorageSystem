package app.groupbase.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.groupbase.TestProps;
import app.groupbase.store.SettingsStore;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PublicUrlTest {

  @Test
  void addressFromConfigWinsAndLosesTrailingSlash() {
    SettingsStore settings = mock(SettingsStore.class);
    when(settings.get(PublicUrl.SETTING)).thenReturn(Optional.of("https://old.cloudpub.ru"));
    PublicUrl url =
        new PublicUrl(
            TestProps.of(Map.of("groupbase.base-url", "https://group.example.ru//")), settings);
    assertThat(url.fixed()).isTrue();
    assertThat(url.get()).hasValue("https://group.example.ru");
  }

  @Test
  void withoutConfigTheAppSetsTheAddress() {
    SettingsStore settings = mock(SettingsStore.class);
    when(settings.get(PublicUrl.SETTING)).thenReturn(Optional.of("https://abc.cloudpub.ru"));
    PublicUrl url = new PublicUrl(TestProps.defaults(), settings);
    assertThat(url.fixed()).isFalse();
    assertThat(url.get()).hasValue("https://abc.cloudpub.ru");

    when(settings.get(PublicUrl.SETTING)).thenReturn(Optional.of("  "));
    assertThat(url.get()).isEmpty();
    url.set(null);
    verify(settings).set(PublicUrl.SETTING, "");
  }
}
