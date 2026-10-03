package app.groupbase.hosts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/**
 * Изменения, которые не дошли до главного компьютера (нет связи): запрос как есть — метод, адрес,
 * тип и тело — и кто его сделал. Лежат в {@code peer/queue/} каталога данных (в копии не попадают),
 * уходят по порядку, как только связь появится.
 */
final class PeerQueue {

  /** Изменение в очереди: {@code <номер>.json} — описание, {@code <номер>.body} — тело. */
  record Item(
      String id, String method, String uri, String contentType, long userId, long at, Path body) {}

  private static final AtomicLong COUNTER = new AtomicLong();

  private final Path dir;

  PeerQueue(Path dataDir) {
    this.dir = dataDir.resolve("peer").resolve("queue");
  }

  /** Временный файл для тела запроса — в том же каталоге, чтобы потом перенести без копирования. */
  synchronized Path tempBody() throws IOException {
    Path tmp = dir.resolve("tmp");
    Files.createDirectories(tmp);
    return Files.createTempFile(tmp, "body-", ".part");
  }

  /** Поставить в очередь; тело переносится в очередь (временного файла больше нет). */
  synchronized Item add(
      String method, String uri, String contentType, long userId, long at, Path body)
      throws IOException {
    Files.createDirectories(dir);
    String id = String.format("%015d-%06d", at, COUNTER.incrementAndGet() % 1_000_000);
    Path target = dir.resolve(id + ".body");
    Files.move(body, target, StandardCopyOption.REPLACE_EXISTING);
    Map<String, Object> meta =
        Map.of(
            "method",
            method,
            "uri",
            uri,
            "contentType",
            contentType == null ? "" : contentType,
            "userId",
            userId,
            "at",
            at);
    Path json = dir.resolve(id + ".json.part");
    Files.write(json, SiteFolder.JSON.writeValueAsBytes(meta));
    Files.move(
        json,
        dir.resolve(id + ".json"),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE);
    return new Item(id, method, uri, contentType, userId, at, target);
  }

  /** Всё по порядку поступления. */
  synchronized List<Item> list() {
    List<Item> out = new ArrayList<>();
    if (!Files.isDirectory(dir)) {
      return out;
    }
    try (Stream<Path> files = Files.list(dir)) {
      for (Path p :
          files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
        String name = p.getFileName().toString();
        String id = name.substring(0, name.length() - ".json".length());
        try {
          JsonNode j = SiteFolder.JSON.readTree(Files.readAllBytes(p));
          out.add(
              new Item(
                  id,
                  j.path("method").asString(""),
                  j.path("uri").asString(""),
                  j.path("contentType").asString(""),
                  j.path("userId").asLong(0),
                  j.path("at").asLong(0),
                  dir.resolve(id + ".body")));
        } catch (IOException | RuntimeException e) {
          // Испорченная запись — убираем, иначе она навсегда остановит очередь.
          remove(id);
        }
      }
    } catch (IOException e) {
      return out;
    }
    return out;
  }

  synchronized int size() {
    if (!Files.isDirectory(dir)) {
      return 0;
    }
    try (Stream<Path> files = Files.list(dir)) {
      return (int) files.filter(f -> f.getFileName().toString().endsWith(".json")).count();
    } catch (IOException e) {
      return 0;
    }
  }

  synchronized void remove(String id) {
    try {
      Files.deleteIfExists(dir.resolve(id + ".json"));
      Files.deleteIfExists(dir.resolve(id + ".body"));
    } catch (IOException e) {
      // уберётся при следующей очистке
    }
  }

  synchronized void clear() {
    for (Item i : list()) {
      remove(i.id());
    }
  }
}
