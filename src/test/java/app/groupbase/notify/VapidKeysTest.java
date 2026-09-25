package app.groupbase.notify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Контакт в подписи VAPID: служба Apple отвергает mailto без настоящего домена (403). */
class VapidKeysTest {

  @Test
  void siteAddressOverLocalMailto() {
    assertThat(VapidKeys.subject("", "https://grp.cloudpub.ru/"))
        .isEqualTo("https://grp.cloudpub.ru");
    assertThat(VapidKeys.subject(null, "https://group.example.org:8443"))
        .isEqualTo("https://group.example.org:8443");
  }

  @Test
  void withoutHttpsAddressFallsBackToProjectPage() {
    assertThat(VapidKeys.subject("", "")).isEqualTo(VapidKeys.PROJECT);
    assertThat(VapidKeys.subject("", "http://192.168.1.5:8080")).isEqualTo(VapidKeys.PROJECT);
    assertThat(VapidKeys.subject("", "https://")).isEqualTo(VapidKeys.PROJECT);
  }

  @Test
  void configuredContactWinsWhenApplePushWouldAcceptIt() {
    assertThat(VapidKeys.subject(" mailto:starosta@mtuci.ru ", "https://grp.cloudpub.ru"))
        .isEqualTo("mailto:starosta@mtuci.ru");
    assertThat(VapidKeys.subject("https://mtuci.ru", "")).isEqualTo("https://mtuci.ru");
  }

  @Test
  void localMailtoIsNeverSent() {
    for (String bad :
        new String[] {
          "mailto:admin@localhost",
          "mailto:admin@127.0.0.1",
          "mailto:admin@groupbase.local",
          "mailto:admin@server",
          "mailto:admin@[::1]",
          "mailto: admin@mtuci.ru",
          "mailto:@mtuci.ru",
          "admin@mtuci.ru",
          "http://mtuci.ru"
        }) {
      assertThat(VapidKeys.usable(bad)).as(bad).isFalse();
      assertThat(VapidKeys.subject(bad, "https://grp.cloudpub.ru"))
          .as(bad)
          .isEqualTo("https://grp.cloudpub.ru");
    }
    assertThat(VapidKeys.usable("mailto:starosta@mtuci.ru")).isTrue();
    assertThat(VapidKeys.usable("https://127.0.0.1:17380")).isTrue();
  }

  @Test
  void pushServiceReasonIsShortAndPlain() {
    assertThat(PushSender.reason("{\"reason\":\"BadJwtToken\"}".getBytes()))
        .isEqualTo("BadJwtToken");
    assertThat(PushSender.reason("{\"code\":404,\"errno\":102,\"message\":\"x\"}".getBytes()))
        .isEqualTo("102");
    assertThat(PushSender.reason("<h1>Unauthorized</h1>\nmore".getBytes()))
        .isEqualTo("h1Unauthorizedh1");
    assertThat(PushSender.reason(new byte[0])).isEmpty();
    assertThat(PushSender.reason("x".repeat(500).getBytes())).hasSize(60);
  }
}
