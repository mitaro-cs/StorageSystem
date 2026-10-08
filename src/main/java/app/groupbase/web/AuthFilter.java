package app.groupbase.web;

import app.groupbase.auth.Actor;
import app.groupbase.auth.SessionService;
import app.groupbase.auth.Tokens;
import app.groupbase.hosts.PeerService;
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

  /** Заголовки второго компьютера хоста: его ключ и чьё это изменение. */
  public static final String PEER_HEADER = "X-Groupbase-Peer";

  public static final String AS_HEADER = "X-Groupbase-As";

  /** Атрибут запроса: номер компьютера хоста, приславшего запрос по ключу. */
  public static final String PEER = "gb.peer";

  private final SessionService sessions;
  private final Cookies cookies;
  private final PeerService peers;

  public AuthFilter(SessionService sessions, Cookies cookies, PeerService peers) {
    this.sessions = sessions;
    this.cookies = cookies;
    this.peers = peers;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String csrfCookie = cookies.readCsrf(req);
    if (!Tokens.looksValid(csrfCookie)) {
      csrfCookie = null;
      cookies.setCsrf(req, res, Tokens.newToken());
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

    // Второй компьютер хоста по своему ключу: обмен данными, а с номером человека — его изменение
    // из окна того компьютера. Вход, выход и окно хоста — только свои, не пересланные.
    String peerHeader = req.getHeader(PEER_HEADER);
    if (api && peerHeader != null) {
      String computer = peers.authenticate(peerHeader).orElse(null);
      if (computer != null) {
        req.setAttribute(PEER, computer);
        String uri = req.getRequestURI();
        String as = req.getHeader(AS_HEADER);
        // Вход и выход от имени человека копия не делает; код входа для другого устройства –
        // можно: его показывают в окне копии, а живёт он на основном (0.9.7).
        boolean auth = uri.startsWith("/api/auth/") && !PeerForwardFilter.relayed(uri);
        if (as != null && !auth && !uri.startsWith("/api/desktop/")) {
          try {
            sessions.forPeer(Long.parseLong(as)).ifPresent(a -> req.setAttribute(ACTOR, a));
          } catch (NumberFormatException e) {
            // нет такого человека — запрос пойдёт без входа
          }
        }
        chain.doFilter(req, res);
        return;
      }
    }

    String token = cookies.readSession(req);
    if (token != null) {
      Actor actor = sessions.resolve(token, Requests.fromThisComputer(req)).orElse(null);
      if (actor != null) {
        req.setAttribute(ACTOR, actor);
        // Срок сессии скользящий — продлеваем и cookie, раз за открытие приложения: кто заходит
        // хотя бы раз в год, входит один раз и больше не вводит пароль.
        if (req.getRequestURI().equals("/api/me") && "GET".equals(req.getMethod())) {
          cookies.setSession(req, res, token, sessions.ttlSeconds());
        }
      } else {
        cookies.clearSession(req, res);
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
