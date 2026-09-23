package app.groupbase.access;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Программа, которую сервер скачивает сам (клиент туннеля): с официальной страницы выпусков, с
 * проверкой SHA-256, записанной в исходниках groupbase. Подменённый файл не запустится.
 */
public record Tool(String name, URI url, String sha256) {

  private static final HttpClient HTTP =
      HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NORMAL)
          .connectTimeout(Duration.ofSeconds(15))
          .build();

  /** Скачивает в {@code target}, если файла ещё нет. Файл делается исполняемым. */
  public Path ensure(Path target) throws IOException {
    if (Files.isRegularFile(target)) {
      return target;
    }
    Files.createDirectories(target.getParent());
    Path part = target.resolveSibling(target.getFileName() + ".part");
    try {
      HttpRequest req =
          HttpRequest.newBuilder(url)
              .timeout(Duration.ofMinutes(5))
              .header("User-Agent", "groupbase")
              .build();
      HttpResponse<InputStream> res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
      if (res.statusCode() != 200) {
        res.body().close();
        throw new IOException("Не удалось скачать " + name + ": ответ " + res.statusCode());
      }
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      try (InputStream in = new DigestInputStream(res.body(), sha);
          OutputStream out = Files.newOutputStream(part)) {
        in.transferTo(out);
      }
      String got = HexFormat.of().formatHex(sha.digest());
      if (!got.equalsIgnoreCase(sha256)) {
        throw new IOException("Скачанный " + name + " не совпал с ожидаемым — файл не запущен");
      }
      part.toFile().setExecutable(true, true);
      Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      return target;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Скачивание прервано", e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    } finally {
      Files.deleteIfExists(part);
    }
  }
}
