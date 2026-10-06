package app.groupbase.web.api;

import app.groupbase.auth.Actor;
import app.groupbase.content.Markdown;
import app.groupbase.content.MaterialService;
import app.groupbase.files.FileStore;
import app.groupbase.files.MarkdownDocx;
import app.groupbase.files.Mime;
import app.groupbase.files.StoredFile;
import app.groupbase.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Загрузка файлов потоком (тело запроса — сам файл, без multipart: открытый текст не пишется во
 * временные файлы) и выдача с расшифровкой на лету.
 */
@RestController
class FileController {

  private static final Logger log = LoggerFactory.getLogger(FileController.class);

  record Uploaded(long id, String name, String mime, long size) {}

  record Ids(List<Long> ids) {}

  record Missing(List<Long> missing) {}

  /** Сколько сохранённых файлов телефон спрашивает за раз. */
  private static final int MAX_IDS = 1000;

  private final FileStore files;
  private final MaterialService materials;

  FileController(FileStore files, MaterialService materials) {
    this.files = files;
    this.materials = materials;
  }

  @PostMapping("/api/files")
  Uploaded upload(Actor actor, HttpServletRequest req) throws IOException {
    if (!materials.canUploadAnywhere(actor)) {
      throw ApiException.forbidden();
    }
    long declared = req.getContentLengthLong();
    if (declared > files.maxBytes()) {
      throw new ApiException(
          org.springframework.http.HttpStatus.CONTENT_TOO_LARGE,
          "too_large",
          "Файл больше " + (files.maxBytes() / 1024 / 1024) + " МБ");
    }
    String header = req.getHeader("X-File-Name");
    String name = header == null ? "file" : URLDecoder.decode(header, StandardCharsets.UTF_8);
    try (InputStream body = req.getInputStream()) {
      StoredFile f = files.store(body, name, actor.id());
      return new Uploaded(f.id(), f.name(), f.mime(), f.size());
    }
  }

  @GetMapping("/api/files/{id}")
  void download(
      Actor actor,
      @PathVariable long id,
      @RequestParam(defaultValue = "false") boolean download,
      HttpServletResponse res)
      throws IOException {
    StoredFile f = files.find(id).orElseThrow(ApiException::notFound);
    if (!materials.canRead(actor, f)) {
      throw ApiException.notFound();
    }
    // Сначала открываем: если содержимого нет на диске (восстановили копию без файлов, удалили
    // вручную), человек получит понятное «файла нет», а не ошибку сервера.
    InputStream in;
    try {
      in = files.open(f);
    } catch (NoSuchFileException e) {
      log.warn("Файл {} есть в базе, но его нет на диске", f.id());
      throw new ApiException(
          HttpStatus.NOT_FOUND,
          "file_missing",
          "Файла пока нет на сервере. Он вернётся сам, когда сайт откроет тот, у кого файл сохранён,"
              + " – или попросите старосту загрузить его заново");
    }
    boolean inline = !download && Mime.inline(f.mime());
    res.setContentType(f.mime().equals("text/plain") ? "text/plain; charset=utf-8" : f.mime());
    res.setContentLengthLong(f.size());
    res.setHeader(
        HttpHeaders.CONTENT_DISPOSITION,
        (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
            .filename(f.name(), StandardCharsets.UTF_8)
            .build()
            .toString());
    res.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=86400");
    res.setHeader(HttpHeaders.ETAG, "\"" + f.sha256() + "\"");
    if (!f.mime().equals("application/pdf")) {
      // Не-PDF файлы открываются в песочнице: даже если в них есть HTML или скрипт, он не
      // выполнится.
      res.setHeader(
          "Content-Security-Policy",
          "sandbox; default-src 'none'; img-src 'self'; media-src 'self'");
    }
    try (in;
        OutputStream out = res.getOutputStream()) {
      in.transferTo(out);
    }
  }

  /**
   * Каких из этих файлов (сохранённых на устройстве) нет на диске сервера: телефон дошлёт их сам.
   * Только те, что человек и так может открыть.
   */
  @PostMapping("/api/files/missing")
  Missing missing(Actor actor, @RequestBody Ids body) {
    List<Long> ids = body.ids() == null ? List.of() : body.ids();
    if (ids.size() > MAX_IDS) {
      ids = ids.subList(0, MAX_IDS);
    }
    return new Missing(
        ids.stream()
            .distinct()
            .map(files::find)
            .flatMap(java.util.Optional::stream)
            .filter(f -> !files.present(f) && materials.canRead(actor, f))
            .map(StoredFile::id)
            .toList());
  }

  /** Содержимое файла, которого нет на сервере, с устройства участника. Проверяется SHA-256. */
  @PutMapping("/api/files/{id}/content")
  Map<String, Boolean> restore(Actor actor, @PathVariable long id, HttpServletRequest req)
      throws IOException {
    StoredFile f = files.find(id).orElseThrow(ApiException::notFound);
    if (!materials.canRead(actor, f)) {
      throw ApiException.notFound();
    }
    try (InputStream body = req.getInputStream()) {
      return Map.of("restored", files.restore(f, body));
    }
  }

  /** Конспект в Markdown — как страница (HTML после санитайзера), для просмотра. */
  @GetMapping("/api/files/{id}/html")
  Map<String, String> markdownHtml(Actor actor, @PathVariable long id) throws IOException {
    return Map.of("html", Markdown.render(markdownText(actor, id).text()));
  }

  /** Конспект в Markdown — документом Word. */
  @GetMapping("/api/files/{id}/docx")
  void markdownDocx(Actor actor, @PathVariable long id, HttpServletResponse res)
      throws IOException {
    Note n = markdownText(actor, id);
    String base = n.file().name().replaceFirst("(?i)\\.(md|markdown)$", "");
    res.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    res.setHeader(
        HttpHeaders.CONTENT_DISPOSITION,
        ContentDisposition.attachment()
            .filename(base + ".docx", StandardCharsets.UTF_8)
            .build()
            .toString());
    res.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
    MarkdownDocx.write(n.text(), base, res.getOutputStream());
  }

  private record Note(StoredFile file, String text) {}

  /** Текст конспекта: только Markdown, не больше 2 МБ, только тому, кто может его читать. */
  private Note markdownText(Actor actor, long id) throws IOException {
    StoredFile f = files.find(id).orElseThrow(ApiException::notFound);
    if (!materials.canRead(actor, f)) {
      throw ApiException.notFound();
    }
    if (!MarkdownDocx.isMarkdown(f.mime(), f.name())) {
      throw ApiException.badRequest("Это не конспект в Markdown (.md)");
    }
    if (f.size() > 2 * 1024 * 1024) {
      throw ApiException.badRequest("Конспект больше 2 МБ – скачайте файл");
    }
    try (InputStream in = files.open(f)) {
      return new Note(f, new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (NoSuchFileException e) {
      throw new ApiException(HttpStatus.NOT_FOUND, "file_missing", "Файла пока нет на сервере");
    }
  }
}
