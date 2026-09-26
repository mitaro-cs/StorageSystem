package app.groupbase.web;

import app.groupbase.hosts.HostService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Сайт сейчас работает на другом компьютере хоста: здесь данные устаревшие, поэтому API закрыт —
 * открыты только состояние переноса ({@code /api/host}), проверка связи и вход окна приложения.
 * Страницы интерфейса отдаются как обычно: они сами покажут страницу ожидания.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class StandbyFilter extends OncePerRequestFilter {

  private static final JsonMapper JSON = JsonMapper.builder().build();

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
    if (hosts.serving()) {
      chain.doFilter(req, res);
      return;
    }
    res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
    res.setCharacterEncoding("UTF-8");
    res.setHeader("Cache-Control", "no-store");
    res.getWriter()
        .write(
            JSON.writeValueAsString(ApiErrorHandler.body("standby", hosts.standbyMessage(), null)));
  }
}
