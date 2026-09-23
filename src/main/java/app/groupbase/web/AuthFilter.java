package app.groupbase.web;

import app.groupbase.auth.Actor;
import app.groupbase.auth.SessionService;
import app.groupbase.auth.Tokens;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Для каждого запроса: выдаёт CSRF-cookie, если её нет; для мутаций /api проверяет double-submit
 * токен и Sec-Fetch-Site; определяет пользователя по cookie сессии.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter {

  public static final String ACTOR = "gb.actor";
  public static final String CSRF_HEADER = "X-CSRF-Token";
  private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final SessionService sessions;
  private final Cookies cookies;

  public AuthFilter(SessionService sessions, Cookies cookies) {
    this.sessions = sessions;
    this.cookies = cookies;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String csrfCookie = cookies.read(req, cookies.csrf);
    if (!Tokens.looksValid(csrfCookie)) {
      csrfCookie = null;
      cookies.setCsrf(res, Tokens.newToken());
    }

    boolean api = req.getRequestURI().startsWith("/api/");
    if (api && !SAFE.contains(req.getMethod())) {
      String site = req.getHeader("Sec-Fetch-Site");
      if (site != null && !site.equals("same-origin") && !site.equals("none")) {
        deny(res, "csrf", "Запрос с чужого сайта отклонён");
        return;
      }
      String header = req.getHeader(CSRF_HEADER);
      if (csrfCookie == null
          || header == null
          || !MessageDigest.isEqual(
              header.getBytes(StandardCharsets.US_ASCII),
              csrfCookie.getBytes(StandardCharsets.US_ASCII))) {
        deny(res, "csrf", "Устаревшая страница: обновите её и повторите");
        return;
      }
    }

    String token = cookies.read(req, cookies.session);
    if (token != null) {
      Actor actor = sessions.resolve(token).orElse(null);
      if (actor != null) {
        req.setAttribute(ACTOR, actor);
      } else {
        cookies.clearSession(res);
      }
    }
    chain.doFilter(req, res);
  }

  private static void deny(HttpServletResponse res, String code, String message)
      throws IOException {
    res.setStatus(HttpServletResponse.SC_FORBIDDEN);
    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
    res.setCharacterEncoding("UTF-8");
    res.getWriter().write(JSON.writeValueAsString(ApiErrorHandler.body(code, message, null)));
  }
}
