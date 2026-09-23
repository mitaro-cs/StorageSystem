package app.groupbase.files;

import java.util.Set;
import org.apache.tika.Tika;

/**
 * Тип файла по содержимому (magic bytes) с подсказкой по имени — только для уточнения внутри того
 * же семейства (zip → docx). Заявленный браузером Content-Type не учитывается.
 */
public final class Mime {

  private static final Tika TIKA = new Tika();

  /** Исполняемые файлы не храним. */
  private static final Set<String> BLOCKED =
      Set.of(
          "application/x-msdownload",
          "application/x-dosexec",
          "application/x-executable",
          "application/x-mach-binary",
          "application/x-sharedlib",
          "application/x-elf",
          "application/vnd.microsoft.portable-executable");

  /** Типы, которые браузер может безопасно показать внутри страницы. */
  private static final Set<String> INLINE =
      Set.of(
          "application/pdf",
          "image/png",
          "image/jpeg",
          "image/gif",
          "image/webp",
          "text/plain",
          "audio/mpeg",
          "audio/ogg",
          "video/mp4",
          "video/webm");

  private Mime() {}

  public static String detect(byte[] head, String name) {
    String t = TIKA.detect(head, name);
    return t == null ? "application/octet-stream" : t;
  }

  public static boolean blocked(String mime) {
    return BLOCKED.contains(mime);
  }

  public static boolean inline(String mime) {
    return INLINE.contains(mime);
  }

  public static boolean image(String mime) {
    return mime.startsWith("image/") && INLINE.contains(mime);
  }

  /** Имя без путей и управляющих символов, не длиннее 200 символов. */
  public static String safeName(String raw) {
    String n = raw == null ? "" : raw;
    n = n.replace('\\', '/');
    n = n.substring(n.lastIndexOf('/') + 1);
    n = n.replaceAll("[\\p{Cntrl}\"<>|:*?]", "").strip();
    if (n.isEmpty() || n.equals(".") || n.equals("..")) {
      n = "file";
    }
    if (n.length() > 200) {
      int dot = n.lastIndexOf('.');
      String ext = dot > 0 && n.length() - dot <= 10 ? n.substring(dot) : "";
      n = n.substring(0, 200 - ext.length()) + ext;
    }
    return n;
  }
}
