package app.groupbase.export;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * ZIP с человеческими именами: русские папки и файлы (UTF-8), запрещённые в Windows символы
 * заменены, одинаковые имена получают « (2)». Пишет прямо в ответ, без временных файлов.
 */
final class ZipWriter implements Closeable {

  private static final int MAX_SEGMENT = 90;

  private final ZipOutputStream zip;
  private final Set<String> used = new HashSet<>();

  ZipWriter(OutputStream out) {
    this.zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
  }

  /** Имя файла или папки, пригодное для любой ОС. */
  static String safe(String name) {
    String s = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
    s = s.replaceAll("^[.\\s]+|[.\\s]+$", "");
    if (s.length() > MAX_SEGMENT) {
      s = s.substring(0, MAX_SEGMENT).strip();
    }
    return s.isEmpty() ? "без названия" : s;
  }

  static String path(String... segments) {
    StringBuilder b = new StringBuilder();
    for (String seg : segments) {
      if (!b.isEmpty()) {
        b.append('/');
      }
      b.append(safe(seg));
    }
    return b.toString();
  }

  /** Свободное имя: «Лекция.pdf» → «Лекция (2).pdf». */
  String unique(String path) {
    String candidate = path;
    int dot = path.lastIndexOf('.');
    int slash = path.lastIndexOf('/');
    boolean ext = dot > slash + 1;
    for (int n = 2; !used.add(candidate.toLowerCase(java.util.Locale.ROOT)); n++) {
      candidate =
          ext
              ? path.substring(0, dot) + " (" + n + ")" + path.substring(dot)
              : path + " (" + n + ")";
    }
    return candidate;
  }

  void text(String path, String content, long time) throws IOException {
    ZipEntry e = new ZipEntry(unique(path));
    e.setTime(time);
    zip.putNextEntry(e);
    zip.write(content.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  /** Записывает файл и возвращает итоговый путь (с « (2)», если имя было занято). */
  String file(String path, InputStream in, long time) throws IOException {
    String name = unique(path);
    ZipEntry e = new ZipEntry(name);
    e.setTime(time);
    zip.putNextEntry(e);
    in.transferTo(zip);
    zip.closeEntry();
    return name;
  }

  /** CSV для Excel с русской локалью: разделитель «;», BOM, кавычки по необходимости. */
  static String csv(List<List<String>> rows) {
    StringBuilder b = new StringBuilder("\uFEFF");
    for (List<String> row : rows) {
      for (int i = 0; i < row.size(); i++) {
        if (i > 0) {
          b.append(';');
        }
        String v = row.get(i) == null ? "" : row.get(i);
        // Начало с = + - @ Excel принял бы за формулу — экранируем апострофом.
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) {
          v = "'" + v;
        }
        if (v.matches("(?s).*[;\"\\r\\n].*")) {
          v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        b.append(v);
      }
      b.append("\r\n");
    }
    return b.toString();
  }

  @Override
  public void close() throws IOException {
    zip.finish();
    zip.flush();
  }
}
