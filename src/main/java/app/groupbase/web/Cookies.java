package app.groupbase.web;

import app.groupbase.config.GroupbaseProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Cookie сессии ({@code HttpOnly; Secure; SameSite=Strict}) и CSRF (читается из JS). По HTTPS
 * используется префикс {@code __Host-}: браузер не даст подменить cookie с поддомена.
 */
@Component
public class Cookies {

  private final boolean secure;
  public final String session;
  public final String csrf;

  public Cookies(GroupbaseProperties props) {
    this.secure = !props.http().insecure();
    this.session = secure ? "__Host-gb_session" : "gb_session";
    this.csrf = secure ? "__Host-gb_csrf" : "gb_csrf";
  }

  public String read(HttpServletRequest req, String name) {
    Cookie[] cookies = req.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie c : cookies) {
      if (c.getName().equals(name)) {
        return c.getValue();
      }
    }
    return null;
  }

  public void setSession(HttpServletResponse res, String token, long maxAgeSeconds) {
    add(res, ResponseCookie.from(session, token).httpOnly(true).maxAge(maxAgeSeconds));
  }

  public void clearSession(HttpServletResponse res) {
    add(res, ResponseCookie.from(session, "").httpOnly(true).maxAge(0));
  }

  public void setCsrf(HttpServletResponse res, String token) {
    add(res, ResponseCookie.from(csrf, token).httpOnly(false).maxAge(Duration.ofDays(365)));
  }

  private void add(HttpServletResponse res, ResponseCookie.ResponseCookieBuilder b) {
    res.addHeader(
        HttpHeaders.SET_COOKIE, b.secure(secure).sameSite("Strict").path("/").build().toString());
  }
}
