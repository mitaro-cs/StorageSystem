package app.groupbase.web;

import app.groupbase.auth.Actor;
import app.groupbase.hosts.PeerService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Изменения в окне второго компьютера хоста (см. {@link PeerService}) уходят главному: запрос
 * пересылается как есть от имени того же человека, ответ главного — окну. Нет связи — изменение
 * применяется здесь и ждёт в очереди. Главный без подтверждённой связи тоже записывает изменения
 * своего окна: вдруг главным уже стал другой компьютер.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PeerForwardFilter extends OncePerRequestFilter {

  private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final PeerService peers;

  public PeerForwardFilter(PeerService peers) {
    this.peers = peers;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    String uri = req.getRequestURI();
    return !uri.startsWith("/api/")
        || SAFE.contains(req.getMethod())
        || (uri.startsWith("/api/auth/") && !relayed(uri))
        || uri.startsWith("/api/desktop/")
        || uri.equals("/api/host")
        || uri.startsWith("/api/host/")
        || uri.equals("/api/setup")
        || uri.startsWith("/api/setup/")
        || uri.equals("/api/health")
        || uri.equals("/api/live")
        // Обновления у каждого компьютера свои – проверяет сам.
        || uri.equals("/api/admin/update-check");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    if (req.getAttribute(AuthFilter.PEER) != null) {
      // Изменение пришло с другого компьютера: принимает его только главный.
      if (peers.role() != PeerService.Role.MAIN) {
        deny(res, "not_main", "Сайт сейчас работает не на этом компьютере");
        return;
      }
      chain.doFilter(req, res);
      return;
    }
    Actor actor = (Actor) req.getAttribute(AuthFilter.ACTOR);
    boolean second = peers.second();
    if (relayed(req.getRequestURI())) {
      if (second && actor != null) {
        relay(req, res, actor, chain);
      } else {
        chain.doFilter(req, res);
      }
      return;
    }
    if (second && hostOnly(req.getRequestURI())) {
      // Доступ для группы и резервные копии – дело компьютера, на котором сайт (0.9.7): на копии
      // их не меняют, а переслать – значит поменять у основного, не видя его настроек.
      String main = peers.view().serving();
      deny(
          res,
          "host_only",
          "Это настраивается на основном компьютере"
              + (main == null ? "" : " «" + main + "»")
              + " – или сделайте основным этот");
      return;
    }
    boolean journal = !second && actor != null && actor.local() && peers.journaling();
    if (actor == null || (!second && !journal)) {
      chain.doFilter(req, res);
      return;
    }
    String uri =
        req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
    Path body = peers.tempBody();
    boolean queued = false;
    try {
      try (InputStream in = req.getInputStream()) {
        Files.copy(in, body, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      if (second) {
        PeerService.Reply r =
            peers.forward(req.getMethod(), uri, req.getContentType(), actor.id(), body);
        if (r != null) {
          res.setStatus(r.status());
          if (r.contentType() != null) {
            res.setContentType(r.contentType());
          }
          res.setHeader("Cache-Control", "no-store");
          res.getOutputStream().write(r.body());
          return;
        }
        // Нет связи с главным: применяем здесь, а главному — когда связь появится.
        res.setHeader("X-Groupbase-Queued", "1");
      }
      HttpServletRequest replay = new BodyRequest(req, body);
      try {
        peers.locally(
            () -> {
              try {
                chain.doFilter(replay, res);
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              } catch (ServletException e) {
                throw new IllegalStateException(e);
              }
            });
      } catch (UncheckedIOException e) {
        throw e.getCause();
      } catch (IllegalStateException e) {
        if (e.getCause() instanceof ServletException se) {
          throw se;
        }
        throw e;
      }
      if (res.getStatus() < 400) {
        peers.enqueue(req.getMethod(), uri, req.getContentType(), actor.id(), body);
        queued = true;
      }
    } finally {
      if (!queued) {
        Files.deleteIfExists(body);
      }
    }
  }

  /**
   * Код входа на другом устройстве (0.9.7): показали в окне копии – код должен жить на основном,
   * куда придёт телефон; иначе «Код не подошёл». Пересылаются, но в очередь не встают.
   */
  static boolean relayed(String uri) {
    return uri.equals("/api/auth/link") || uri.equals("/api/auth/link/status");
  }

  /** Настройки самого компьютера с сайтом: туннель и копии данных. */
  static boolean hostOnly(String uri) {
    return uri.startsWith("/api/admin/access") || uri.startsWith("/api/admin/backups");
  }

  /** Переслать основному как есть; нет связи – ответить здесь, без очереди. */
  private void relay(
      HttpServletRequest req, HttpServletResponse res, Actor actor, FilterChain chain)
      throws IOException, ServletException {
    Path body = peers.tempBody();
    try {
      try (InputStream in = req.getInputStream()) {
        Files.copy(in, body, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      PeerService.Reply r =
          peers.forward(
              req.getMethod(), req.getRequestURI(), req.getContentType(), actor.id(), body, false);
      if (r != null) {
        res.setStatus(r.status());
        if (r.contentType() != null) {
          res.setContentType(r.contentType());
        }
        res.setHeader("Cache-Control", "no-store");
        res.getOutputStream().write(r.body());
        return;
      }
      chain.doFilter(new BodyRequest(req, body), res);
    } finally {
      Files.deleteIfExists(body);
    }
  }

  private static void deny(HttpServletResponse res, String code, String message)
      throws IOException {
    res.setStatus(HttpServletResponse.SC_CONFLICT);
    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
    res.setCharacterEncoding("UTF-8");
    res.getWriter().write(JSON.writeValueAsString(ApiErrorHandler.body(code, message, null)));
  }

  /** Запрос с телом из файла — тело уже прочитано из сети, чтобы его можно было переслать. */
  static final class BodyRequest extends HttpServletRequestWrapper {
    private final Path body;

    BodyRequest(HttpServletRequest req, Path body) {
      super(req);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      InputStream in = Files.newInputStream(body);
      return new ServletInputStream() {
        private boolean done;

        @Override
        public int read() throws IOException {
          int b = in.read();
          done = b < 0;
          return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
          int n = in.read(buf, off, len);
          done = n < 0;
          return n;
        }

        @Override
        public boolean isFinished() {
          return done;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException();
        }

        @Override
        public void close() throws IOException {
          in.close();
        }
      };
    }

    @Override
    public BufferedReader getReader() throws IOException {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
  }
}
