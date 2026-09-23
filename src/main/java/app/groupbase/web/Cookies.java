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
 *
 * <p>Обычно сервер стоит за HTTPS-прокси, и Secure ставится всегда. В приложении хоста окно на его
 * компьютере ходит по HTTP на 127.0.0.1, а участники — по HTTPS через туннель: там Secure решается
 * по схеме конкретного запроса.
 */
@Component
public class Cookies {

  private static final String SESSION = "gb_session";
  private static final String CSRF = "gb_csrf";
  private static final String HOST = "__Host-";

  private final boolean insecure;
  private final boolean perRequest;

  public Cookies(GroupbaseProperties props) {
    this.insecure = props.http().insecure();
    this.perRequest = props.desktop().enabled();
  }

  public boolean secure(HttpServletRequest req) {
    if (insecure) {
      return false;
    }
    return !perRequest || req.isSecure();
  }

  public String readSession(HttpServletRequest req) {
    return read(req, name(req, SESSION));
  }

  public String readCsrf(HttpServletRequest req) {
    return read(req, name(req, CSRF));
  }

  private String name(HttpServletRequest req, String base) {
    return secure(req) ? HOST + base : base;
  }

  private static String read(HttpServletRequest req, String name) {
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

  public void setSession(
      HttpServletRequest req, HttpServletResponse res, String token, long maxAgeSeconds) {
    add(
        req,
        res,
        ResponseCookie.from(name(req, SESSION), token).httpOnly(true).maxAge(maxAgeSeconds));
  }

  public void clearSession(HttpServletRequest req, HttpServletResponse res) {
    add(req, res, ResponseCookie.from(name(req, SESSION), "").httpOnly(true).maxAge(0));
  }

  public void setCsrf(HttpServletRequest req, HttpServletResponse res, String token) {
    add(
        req,
        res,
        ResponseCookie.from(name(req, CSRF), token).httpOnly(false).maxAge(Duration.ofDays(365)));
  }

  private void add(
      HttpServletRequest req, HttpServletResponse res, ResponseCookie.ResponseCookieBuilder b) {
    res.addHeader(
        HttpHeaders.SET_COOKIE,
        b.secure(secure(req)).sameSite("Strict").path("/").build().toString());
  }
}
