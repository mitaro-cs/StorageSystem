package app.groupbase.web;

import app.groupbase.config.GroupbaseProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Заголовки безопасности на каждый ответ. CSP запрещает inline-скрипты: хеши единственного
 * загрузочного скрипта SvelteKit считаются один раз при старте из index.html.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {

  private static final Pattern INLINE_SCRIPT =
      Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>([\\s\\S]*?)</script>");

  private final String csp;
  private final boolean hsts;

  public SecurityHeadersFilter(GroupbaseProperties props) throws IOException {
    this.hsts = !props.http().insecure();
    this.csp = buildCsp(inlineScriptHashes());
  }

  static String buildCsp(List<String> scriptHashes) {
    StringBuilder script = new StringBuilder("'self'");
    for (String h : scriptHashes) {
      script.append(" '").append(h).append('\'');
    }
    return String.join(
        "; ",
        "default-src 'self'",
        "script-src " + script,
        "style-src 'self' 'unsafe-inline'",
        "img-src 'self' data: blob:",
        "font-src 'self'",
        "connect-src 'self'",
        "media-src 'self' blob:",
        "frame-src 'self' blob:",
        "worker-src 'self'",
        "manifest-src 'self'",
        "object-src 'none'",
        "base-uri 'none'",
        "form-action 'self'",
        "frame-ancestors 'self'");
  }

  static List<String> inlineScriptHashes() throws IOException {
    List<String> hashes = new ArrayList<>();
    try (InputStream in = StaticWebConfig.index().getInputStream()) {
      String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      Matcher m = INLINE_SCRIPT.matcher(html);
      while (m.find()) {
        if (!m.group(1).isBlank()) {
          hashes.add("sha256-" + sha256(m.group(1)));
        }
      }
    }
    return hashes;
  }

  private static String sha256(String s) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(d);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    // Для выдачи файлов политику задаёт FileController: песочница для всего, кроме PDF
    // (встроенный просмотрщик Chrome не работает под object-src 'none' и sandbox).
    if (!req.getRequestURI().startsWith("/api/files/")) {
      res.setHeader("Content-Security-Policy", csp);
    }
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("X-Frame-Options", "SAMEORIGIN");
    res.setHeader("Referrer-Policy", "no-referrer");
    res.setHeader(
        "Permissions-Policy",
        "camera=(), microphone=(), geolocation=(), payment=(), usb=(), browsing-topics=()");
    res.setHeader("Cross-Origin-Opener-Policy", "same-origin");
    res.setHeader("Cross-Origin-Resource-Policy", "same-origin");
    res.setHeader("X-Robots-Tag", "noindex, nofollow, noarchive");
    if (hsts) {
      res.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
    }
    chain.doFilter(req, res);
  }
}
