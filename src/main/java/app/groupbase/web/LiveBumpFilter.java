package app.groupbase.web;

import app.groupbase.sync.LiveUpdates;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Любое успешное изменение через API – люди вступили, роль, группа, настройки, предмет – поднимает
 * версию данных (0.9.7, просьба владельца: «у всех всегда актуальная информация»). Открытые
 * страницы узнают об этом через {@link LiveUpdates} за секунду, копия на втором компьютере хоста –
 * на следующем шаге. Вход, опросы и личные настройки версию не трогают: от них у других ничего не
 * меняется.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class LiveBumpFilter extends OncePerRequestFilter {

  private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

  /** Запросы-изменения, которые ничего не меняют для других (вход, опросы, своё оформление). */
  static final List<String> QUIET =
      List.of(
          "/api/auth/login",
          "/api/auth/logout",
          "/api/auth/link",
          "/api/live",
          "/api/me/preferences",
          "/api/me/notifications",
          "/api/push/",
          "/api/files/missing",
          "/api/host",
          "/api/desktop/",
          "/api/setup",
          "/api/admin/update-check",
          "/api/admin/access",
          "/api/admin/backups");

  private final LiveUpdates live;

  public LiveBumpFilter(LiveUpdates live) {
    this.live = live;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    return !counts(req.getMethod(), req.getRequestURI());
  }

  static boolean counts(String method, String uri) {
    return uri.startsWith("/api/")
        && !SAFE.contains(method)
        && QUIET.stream().noneMatch(uri::startsWith)
        && !uri.endsWith("/schedule/preview");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    chain.doFilter(req, res);
    if (res.getStatus() < 400) {
      live.bump();
    }
  }
}
