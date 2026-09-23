package app.groupbase.web;

import java.io.IOException;
import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.EncodedResourceResolver;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Отдаёт собранный SvelteKit из {@code classpath:/static}. Файлы с хешем в имени кешируются
 * навсегда, остальное — с ревалидацией. Любой путь без расширения, кроме {@code /api}, получает
 * index.html (клиентский роутинг). Предсжатые .br/.gz отдаются, если браузер их принимает.
 */
@Configuration
class StaticWebConfig implements WebMvcConfigurer {

  static final String INDEX = "static/index.html";
  static final String PLACEHOLDER = "placeholder/index.html";

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/_app/immutable/**")
        .addResourceLocations("classpath:/static/_app/immutable/")
        .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
        .resourceChain(true)
        .addResolver(new EncodedResourceResolver())
        .addResolver(new PathResourceResolver());

    registry
        .addResourceHandler("/**")
        .addResourceLocations("classpath:/static/")
        .setCacheControl(CacheControl.noCache().cachePrivate())
        .resourceChain(true)
        .addResolver(new EncodedResourceResolver())
        .addResolver(new SpaResolver());
  }

  /** Возвращает файл, если он есть; иначе index.html для путей приложения. */
  static final class SpaResolver extends PathResourceResolver {
    @Override
    protected Resource getResource(String path, Resource location) throws IOException {
      if (!path.isEmpty()) {
        Resource r = location.createRelative(path);
        if (r.isReadable() && r.contentLength() >= 0 && !path.endsWith("/")) {
          return r;
        }
      }
      if (path.startsWith("api/") || lastSegment(path).contains(".")) {
        return null;
      }
      return index();
    }

    private static String lastSegment(String path) {
      int slash = path.lastIndexOf('/');
      return slash < 0 ? path : path.substring(slash + 1);
    }
  }

  /** index.html собранного фронта или встроенная заглушка, если фронт не собран. */
  static Resource index() {
    Resource built = new ClassPathResource(INDEX);
    return built.exists() ? built : new ClassPathResource(PLACEHOLDER);
  }
}
