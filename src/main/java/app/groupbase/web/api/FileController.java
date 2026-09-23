package app.groupbase.web.api;

import app.groupbase.auth.Actor;
import app.groupbase.content.MaterialService;
import app.groupbase.files.FileStore;
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
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Загрузка файлов потоком (тело запроса — сам файл, без multipart: открытый текст не пишется во
 * временные файлы) и выдача с расшифровкой на лету.
 */
@RestController
class FileController {

  record Uploaded(long id, String name, String mime, long size) {}

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
    try (InputStream in = files.open(f);
        OutputStream out = res.getOutputStream()) {
      in.transferTo(out);
    }
  }
}
