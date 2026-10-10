package app.groupbase.files;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Текст файлов для поиска (1.0.2): PDF, документы Word и OpenDocument, презентации, таблицы,
 * конспекты .md и другой текст. Фоновая задача раз в полминуты берёт прикреплённые файлы без текста
 * (материалы, вложения заданий и новостей) – по одному, без спешки: сервер на компьютере старосты.
 * Текст – только в индексе FTS5 ({@code file_search}), отдельно не хранится.
 */
@Service
public class FileText {

  /** Больше не читаем – файл учебный, а память у компьютера хоста не бесконечная. */
  static final long MAX_BYTES = 40L * 1024 * 1024;

  static final int MAX_CHARS = 1_000_000;
  static final int MAX_PAGES = 400;

  /** Одна часть архива docx/pptx – не больше (защита от «zip-бомбы»). */
  static final int MAX_ENTRY = 20 * 1024 * 1024;

  static final int BATCH = 10;

  private static final Set<String> TEXT =
      Set.of(
          "md",
          "markdown",
          "txt",
          "csv",
          "tex",
          "json",
          "xml",
          "html",
          "htm",
          "c",
          "h",
          "cpp",
          "hpp",
          "cs",
          "java",
          "kt",
          "py",
          "js",
          "ts",
          "go",
          "rs",
          "sql",
          "sh",
          "yaml",
          "yml");

  private final JdbcClient db;
  private final FileStore store;
  private final Clock clock;
  private final AtomicBoolean running = new AtomicBoolean();

  /** Файлов нет на этом компьютере (копия ещё не получила) – до перезапуска не пробуем снова. */
  private final Set<Long> absent = ConcurrentHashMap.newKeySet();

  public FileText(JdbcClient db, FileStore store, Clock clock) {
    this.db = db;
    this.store = store;
    this.clock = clock;
  }

  @Scheduled(initialDelayString = "PT20S", fixedDelayString = "PT30S")
  void schedule() {
    if (running.compareAndSet(false, true)) {
      Thread.ofVirtual()
          .name("file-text")
          .start(
              () -> {
                try {
                  while (batch(BATCH) == BATCH) {
                    // Ещё есть – следующая порция сразу.
                  }
                } catch (RuntimeException ex) {
                  // База занята или файл испорчен – в следующий раз.
                } finally {
                  running.set(false);
                }
              });
    }
  }

  /** Всё, что ждёт, – сейчас (проверки и «Управление»); сколько файлов обработано. */
  public int indexNow() {
    int total = 0;
    int n;
    while ((n = batch(BATCH)) > 0) {
      total += n;
    }
    return total;
  }

  /** Порция прикреплённых файлов без текста; сколько обработано. */
  int batch(int limit) {
    List<Long> ids =
        db.sql(
                """
                SELECT f.id FROM files f
                WHERE NOT EXISTS (SELECT 1 FROM file_text t WHERE t.file_id = f.id)
                  AND (EXISTS (SELECT 1 FROM materials m WHERE m.file_id = f.id)
                    OR EXISTS (SELECT 1 FROM homework_attachments a WHERE a.file_id = f.id)
                    OR EXISTS (SELECT 1 FROM post_attachments p WHERE p.file_id = f.id))
                ORDER BY f.id DESC LIMIT :n
                """)
            .param("n", limit + absent.size())
            .query(Long.class)
            .list();
    int done = 0;
    for (long id : ids) {
      if (done >= limit) {
        break;
      }
      if (absent.contains(id)) {
        continue;
      }
      store.find(id).ifPresent(this::index);
      done++;
    }
    return done;
  }

  /** Достать текст и положить в индекс. */
  void index(StoredFile f) {
    if (!store.present(f)) {
      absent.add(f.id());
      return;
    }
    String status;
    String text = "";
    if (kind(f.name(), f.mime()) == null) {
      status = "skipped";
    } else if (f.size() > MAX_BYTES) {
      status = "skipped";
    } else {
      try (InputStream in = store.open(f)) {
        text = extract(f.name(), f.mime(), in.readNBytes((int) MAX_BYTES + 1));
        status = text.isBlank() ? "empty" : "ok";
      } catch (IOException | RuntimeException ex) {
        status = "failed";
      }
    }
    save(f.id(), status, text);
  }

  private synchronized void save(long fileId, String status, String text) {
    db.sql("DELETE FROM file_search WHERE rowid = ?").param(fileId).update();
    if ("ok".equals(status)) {
      db.sql("INSERT INTO file_search (rowid, body) VALUES (?, ?)").params(fileId, text).update();
    }
    db.sql(
            "INSERT OR REPLACE INTO file_text (file_id, status, chars, extracted_at)"
                + " VALUES (?, ?, ?, ?)")
        .params(fileId, status, text.length(), clock.millis())
        .update();
  }

  /** Что это за файл для поиска; null – текста из него не достать. */
  static String kind(String name, String mime) {
    String ext = ext(name);
    String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
    if (ext.equals("pdf") || m.equals("application/pdf")) {
      return "pdf";
    }
    if (ext.equals("docx")) {
      return "docx";
    }
    if (ext.equals("pptx")) {
      return "pptx";
    }
    if (ext.equals("xlsx")) {
      return "xlsx";
    }
    if (ext.equals("odt") || ext.equals("odp") || ext.equals("ods")) {
      return "odf";
    }
    if (TEXT.contains(ext) || m.startsWith("text/")) {
      return "text";
    }
    return null;
  }

  static String ext(String name) {
    int dot = name == null ? -1 : name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  /** Текст файла: пробелы схлопнуты, «ё» – как «е» (так ищет и основной индекс). */
  static String extract(String name, String mime, byte[] data) throws IOException {
    String kind = kind(name, mime);
    if (kind == null || data.length > MAX_BYTES) {
      return "";
    }
    String raw =
        switch (kind) {
          case "pdf" -> pdf(data);
          case "docx" -> xmlText(zipParts(data, "word/document.xml"::equals));
          case "pptx" ->
              xmlText(zipParts(data, n -> n.startsWith("ppt/slides/slide") && n.endsWith(".xml")));
          case "xlsx" -> xmlText(zipParts(data, "xl/sharedStrings.xml"::equals));
          case "odf" -> xmlText(zipParts(data, "content.xml"::equals));
          default -> new String(data, StandardCharsets.UTF_8);
        };
    String clean = raw.replace('\u0000', ' ').replaceAll("[ \\t\\x0B\\f\\r]+", " ");
    clean = clean.replaceAll("\\n\\s*\\n+", "\n").strip().replace('ё', 'е').replace('Ё', 'Е');
    return clean.length() > MAX_CHARS ? clean.substring(0, MAX_CHARS) : clean;
  }

  private static String pdf(byte[] data) throws IOException {
    try (PDDocument doc = Loader.loadPDF(data)) {
      PDFTextStripper s = new PDFTextStripper();
      s.setSortByPosition(true);
      s.setEndPage(Math.min(doc.getNumberOfPages(), MAX_PAGES));
      return s.getText(doc);
    }
  }

  /** Нужные части архива (docx, pptx – это zip с XML), по порядку имён: слайд 2 раньше 10. */
  private static List<String> zipParts(byte[] data, java.util.function.Predicate<String> want)
      throws IOException {
    record Part(String name, String xml) {}
    List<Part> parts = new ArrayList<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
      ZipEntry e;
      while ((e = zip.getNextEntry()) != null) {
        if (!e.isDirectory() && want.test(e.getName())) {
          byte[] b = zip.readNBytes(MAX_ENTRY);
          parts.add(new Part(e.getName(), new String(b, StandardCharsets.UTF_8)));
        }
      }
    }
    parts.sort(Comparator.comparing(Part::name, FileText::natural));
    return parts.stream().map(Part::xml).toList();
  }

  private static final Pattern NUMBER = Pattern.compile("\\d+");

  private static int natural(String a, String b) {
    Matcher ma = NUMBER.matcher(a);
    Matcher mb = NUMBER.matcher(b);
    if (ma.find() && mb.find() && a.substring(0, ma.start()).equals(b.substring(0, mb.start()))) {
      int c = Long.compare(Long.parseLong(ma.group()), Long.parseLong(mb.group()));
      if (c != 0) {
        return c;
      }
    }
    return a.compareTo(b);
  }

  private static final Pattern PARAGRAPH_END =
      Pattern.compile(
          "</(?:w:p|a:p|text:p|text:h|si|w:tab|w:br)>|<(?:w:br|w:tab|text:line-break)/>");
  private static final Pattern TAG = Pattern.compile("<[^>]*>");
  private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9a-fA-F]+|amp|lt|gt|quot|apos);");

  /** Текст из XML документа: конец абзаца – перенос строки, теги – прочь, сущности – буквами. */
  static String xmlText(List<String> parts) {
    StringBuilder out = new StringBuilder();
    for (String xml : parts) {
      String t = PARAGRAPH_END.matcher(xml).replaceAll("\n");
      t = TAG.matcher(t).replaceAll("");
      out.append(unescape(t)).append('\n');
    }
    return out.toString();
  }

  private static String unescape(String s) {
    Matcher m = ENTITY.matcher(s);
    StringBuilder b = new StringBuilder();
    while (m.find()) {
      String e = m.group(1);
      String r =
          switch (e) {
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos" -> "'";
            default -> {
              try {
                int cp =
                    e.startsWith("#x") || e.startsWith("#X")
                        ? Integer.parseInt(e.substring(2), 16)
                        : Integer.parseInt(e.substring(1));
                yield Character.isValidCodePoint(cp) ? Character.toString(cp) : " ";
              } catch (NumberFormatException ex) {
                yield " ";
              }
            }
          };
      m.appendReplacement(b, Matcher.quoteReplacement(r));
    }
    m.appendTail(b);
    return b.toString();
  }
}
