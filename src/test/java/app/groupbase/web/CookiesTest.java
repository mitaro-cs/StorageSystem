package app.groupbase.web;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.TestProps;
import app.groupbase.config.GroupbaseProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class CookiesTest {

  private static GroupbaseProperties props(boolean insecure) {
    return TestProps.of(Map.of("groupbase.http.insecure", String.valueOf(insecure)));
  }

  @Test
  void httpsCookiesUseHostPrefixAndSecure() {
    Cookies c = new Cookies(props(false));
    MockHttpServletResponse res = new MockHttpServletResponse();
    c.setSession(res, "tok", 100);
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
    c.setCsrf(res, "abc");
    assertThat(res.getHeader("Set-Cookie"))
        .startsWith("__Host-gb_csrf=abc")
        .doesNotContain("HttpOnly");
  }

  @Test
  void lanModeDropsSecure() {
    Cookies c = new Cookies(props(true));
    MockHttpServletResponse res = new MockHttpServletResponse();
    c.setSession(res, "tok", 100);
    assertThat(res.getHeader("Set-Cookie")).startsWith("gb_session=").doesNotContain("Secure");
  }
}
