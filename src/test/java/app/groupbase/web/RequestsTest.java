package app.groupbase.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RequestsTest {

  private static boolean local(String ip) {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setRemoteAddr(ip);
    return Requests.fromThisComputer(req);
  }

  @Test
  void onlyLoopbackCountsAsThisComputer() {
    assertThat(local("127.0.0.1")).isTrue();
    assertThat(local("::1")).isTrue();
    assertThat(local("0:0:0:0:0:0:0:1")).isTrue();
    assertThat(local("192.168.1.10")).isFalse();
    assertThat(local("10.0.0.2")).isFalse();
    assertThat(local("2001:db8::1")).isFalse();
  }

  @Test
  void namesAndGarbageAreNotResolved() {
    // Имя вместо адреса не ищем в DNS: «localhost» подставить нельзя.
    assertThat(local("localhost")).isFalse();
    assertThat(local("")).isFalse();
    assertThat(local("evil.example")).isFalse();
  }
}
