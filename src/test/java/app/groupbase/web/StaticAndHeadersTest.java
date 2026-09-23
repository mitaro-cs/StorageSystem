package app.groupbase.web;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class StaticAndHeadersTest extends IntegrationTest {

  private final HttpClient http = HttpClient.newHttpClient();

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(url(path))).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void healthIsOk() throws Exception {
    HttpResponse<String> r = get("/api/health");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).contains("\"ok\"");
  }

  @Test
  void securityHeadersOnEveryResponse() throws Exception {
    HttpResponse<String> r = get("/api/health");
    assertThat(r.headers().firstValue("Content-Security-Policy").orElseThrow())
        .contains("default-src 'self'")
        .contains("object-src 'none'")
        .doesNotContain("unsafe-eval");
    assertThat(r.headers().firstValue("X-Robots-Tag"))
        .hasValueSatisfying(v -> v.contains("noindex"));
    assertThat(r.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
    assertThat(r.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
  }

  @Test
  void clientRoutesGetIndexHtml() throws Exception {
    HttpResponse<String> r = get("/subjects/12/materials");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/html");
    assertThat(r.body()).contains("<html lang=\"ru\"");
  }

  @Test
  void missingAssetsAndApiAre404() throws Exception {
    assertThat(get("/missing.js").statusCode()).isEqualTo(404);
    assertThat(get("/api/does-not-exist").statusCode()).isEqualTo(404);
  }

  @Test
  void robotsDisallowsEverything() throws Exception {
    assertThat(get("/robots.txt").body()).contains("Disallow: /");
  }

  @Test
  void cspAllowsOnlyHashedInlineScripts() {
    String csp = SecurityHeadersFilter.buildCsp(java.util.List.of("sha256-abc"));
    String scriptSrc =
        java.util.Arrays.stream(csp.split("; "))
            .filter(d -> d.startsWith("script-src"))
            .findFirst()
            .orElseThrow();
    assertThat(scriptSrc).isEqualTo("script-src 'self' 'sha256-abc'");
  }
}
