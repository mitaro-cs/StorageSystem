package app.groupbase.web;

import app.groupbase.hosts.HostService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Сайт сейчас работает на другом компьютере хоста: здесь данные устаревшие, поэтому API закрыт —
 * открыты только состояние переноса ({@code /api/host}), проверка связи и вход окна приложения.
 * Страницы интерфейса отдаются как обычно: они сами покажут страницу ожидания. Пока сайт забирают
 * по коду переноса, читать можно, а изменения отклоняются.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class StandbyFilter extends OncePerRequestFilter {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

  private final HostService hosts;

  public StandbyFilter(HostService hosts) {
    this.hosts = hosts;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    String uri = req.getRequestURI();
    return !uri.startsWith("/api/")
        || uri.equals("/api/host")
        || uri.startsWith("/api/host/")
        || uri.equals("/api/health")
        || uri.startsWith("/api/desktop/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    if (!hosts.serving()) {
      deny(res, "standby", hosts.standbyMessage());
      return;
    }
    if (hosts.moving() && !SAFE.contains(req.getMethod())) {
      // Сайт забирает другой компьютер по коду: изменения отсюда туда уже не попадут.
      deny(res, "moving", "Сайт переезжает на другой компьютер — повторите через пару минут");
      return;
    }
    chain.doFilter(req, res);
  }

  private static void deny(HttpServletResponse res, String code, String message)
      throws IOException {
    res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
    res.setCharacterEncoding("UTF-8");
    res.setHeader("Cache-Control", "no-store");
    res.getWriter().write(JSON.writeValueAsString(ApiErrorHandler.body(code, message, null)));
  }
}
