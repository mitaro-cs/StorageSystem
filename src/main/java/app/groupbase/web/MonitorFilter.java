package app.groupbase.web;

import app.groupbase.status.Monitor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Время ответа и ошибки API для мониторинга хоста (1.0.2). Без потока событий, опросов и самой
 * проверки здоровья – они бы заслонили настоящие запросы людей.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class MonitorFilter extends OncePerRequestFilter {

  private final Monitor monitor;

  public MonitorFilter(Monitor monitor) {
    this.monitor = monitor;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    return !counts(req.getRequestURI());
  }

  static boolean counts(String uri) {
    return uri.startsWith("/api/")
        && !uri.startsWith("/api/live")
        && !uri.equals("/api/presence")
        && !uri.equals("/api/health")
        && !uri.startsWith("/api/admin/monitor")
        && !uri.startsWith("/api/host/peer/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    long start = System.nanoTime();
    try {
      chain.doFilter(req, res);
    } finally {
      monitor.request((System.nanoTime() - start) / 1_000_000, res.getStatus());
    }
  }
}
