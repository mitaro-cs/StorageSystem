package app.groupbase.backup;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Номер последней миграции в этой версии программы — без запуска Flyway. */
final class Migrations {

  private static final Pattern NAME = Pattern.compile("V(\\d+)__.*\\.sql");
  private static volatile int latest = -1;

  private Migrations() {}

  static int latest() throws IOException {
    if (latest < 0) {
      int max = 0;
      for (Resource r :
          new PathMatchingResourcePatternResolver()
              .getResources("classpath*:db/migration/V*.sql")) {
        Matcher m = NAME.matcher(r.getFilename() == null ? "" : r.getFilename());
        if (m.matches()) {
          max = Math.max(max, Integer.parseInt(m.group(1)));
        }
      }
      latest = max;
    }
    return latest;
  }
}
