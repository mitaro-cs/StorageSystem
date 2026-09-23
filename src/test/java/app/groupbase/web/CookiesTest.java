package app.groupbase.web;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.TestProps;
import app.groupbase.config.GroupbaseProperties;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CookiesTest {

  private static GroupbaseProperties props(boolean insecure) {
    return TestProps.of(Map.of("groupbase.http.insecure", String.valueOf(insecure)));
  }

  private static MockHttpServletRequest request(boolean secure) {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setSecure(secure);
    return req;
  }

  @Test
  void httpsCookiesUseHostPrefixAndSecure() {
    Cookies c = new Cookies(props(false));
    MockHttpServletResponse res = new MockHttpServletResponse();
    // За HTTPS-прокси сам запрос приходит по HTTP, но cookie всё равно Secure.
    c.setSession(request(false), res, "tok", 100);
    String h = res.getHeader("Set-Cookie");
    assertThat(h)
        .startsWith("__Host-gb_session=tok")
        .contains("Secure")
        .contains("HttpOnly")
        .contains("SameSite=Strict")
        .contains("Path=/")
        .doesNotContain("Domain");
  }

  @Test
  void csrfCookieIsReadableByScript() {
    Cookies c = new Cookies(props(false));
    MockHttpServletResponse res = new MockHttpServletResponse();
    c.setCsrf(request(false), res, "abc");
    assertThat(res.getHeader("Set-Cookie"))
        .startsWith("__Host-gb_csrf=abc")
        .doesNotContain("HttpOnly");
  }

  @Test
  void lanModeDropsSecure() {
    Cookies c = new Cookies(props(true));
    MockHttpServletResponse res = new MockHttpServletResponse();
    c.setSession(request(true), res, "tok", 100);
    assertThat(res.getHeader("Set-Cookie")).startsWith("gb_session=").doesNotContain("Secure");
  }

  @Test
  void desktopDecidesPerRequest() {
    Cookies c = new Cookies(TestProps.of(Map.of("groupbase.desktop.enabled", "true")));

    // Окно хоста на его компьютере: http://127.0.0.1 — без Secure и префикса.
    MockHttpServletResponse local = new MockHttpServletResponse();
    c.setSession(request(false), local, "tok", 100);
    assertThat(local.getHeader("Set-Cookie")).startsWith("gb_session=").doesNotContain("Secure");

    // Участник через туннель: HTTPS — как на обычном сервере.
    MockHttpServletResponse tunnel = new MockHttpServletResponse();
    c.setSession(request(true), tunnel, "tok", 100);
    assertThat(tunnel.getHeader("Set-Cookie")).startsWith("__Host-gb_session=").contains("Secure");

    // Читается cookie под тем именем, что соответствует схеме запроса.
    MockHttpServletRequest https = request(true);
    https.setCookies(new Cookie("gb_session", "plain"), new Cookie("__Host-gb_session", "host"));
    assertThat(c.readSession(https)).isEqualTo("host");
    MockHttpServletRequest http = request(false);
    http.setCookies(new Cookie("gb_session", "plain"), new Cookie("__Host-gb_session", "host"));
    assertThat(c.readSession(http)).isEqualTo("plain");
  }
}
