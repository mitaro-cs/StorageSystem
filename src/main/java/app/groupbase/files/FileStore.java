package app.groupbase.files;

import app.groupbase.config.GroupbaseProperties;
import app.groupbase.config.Secrets;
import app.groupbase.store.Rows;
import app.groupbase.web.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Хранилище файлов: данные шифруются на лету при загрузке (открытый текст на диск не попадает), на
 * диске — {@code files/ab/<uuid>}. Метаданные — в таблице files.
 */
@Service
public class FileStore {

  private static final Logger log = LoggerFactory.getLogger(FileStore.class);
  private static final int SNIFF = 64 * 1024;

  static final RowMapper<StoredFile> MAPPER =
      (rs, i) ->
          new StoredFile(
              rs.getLong("id"),
              rs.getString("uuid"),
              rs.getString("name"),
              rs.getString("mime"),
              rs.getLong("size"),
              rs.getString("sha256"),
              Rows.longOrNull(rs, "uploaded_by"),
              rs.getLong("created_at"));

  private final JdbcClient db;
  private final Path root;
  private final byte[] key;
  private final long maxBytes;
  private final Clock clock;

  public FileStore(JdbcClient db, GroupbaseProperties props, Secrets secrets, Clock clock) {
    this.db = db;
    this.root = props.filesDir();
    this.key = secrets.filesKey();
    this.maxBytes = props.uploads().maxBytes();
    this.clock = clock;
  }

  public long maxBytes() {
    return maxBytes;
  }

  /** Сохраняет поток: определяет тип по содержимому, шифрует, считает SHA-256. */
  public StoredFile store(InputStream body, String declaredName, Long uploader) throws IOException {
    String name = Mime.safeName(declaredName);
    byte[] head = body.readNBytes(SNIFF);
    String mime = Mime.detect(head, name);
    if (Mime.blocked(mime)) {
      throw new ApiException(
          HttpStatus.UNSUPPORTED_MEDIA_TYPE, "file_type", "Исполняемые файлы загружать нельзя");
    }
    String uuid = UUID.randomUUID().toString();
    Path target = path(uuid);
    Files.createDirectories(target.getParent());
    Path part = target.resolveSibling(uuid + ".part");
    MessageDigest sha = sha256();
    long size;
    try (InputStream in = new SequenceInputStream(new ByteArrayInputStream(head), body);
        OutputStream enc = FileCrypto.encrypt(Files.newOutputStream(part), key, uuid);
        OutputStream out = new DigestOutputStream(enc, sha)) {
      size = copyLimited(in, out);
    } catch (IOException | RuntimeException e) {
      Files.deleteIfExists(part);
      throw e;
    }
    Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
    String hash = HexFormat.of().formatHex(sha.digest());
    long id =
        db.sql(
                "INSERT INTO files (uuid, name, mime, size, sha256, uploaded_by, created_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id")
            .params(uuid, name, mime, size, hash, uploader, clock.millis())
            .query(Long.class)
            .single();
    return find(id).orElseThrow();
  }

  private long copyLimited(InputStream in, OutputStream out) throws IOException {
    byte[] buf = new byte[32 * 1024];
    long total = 0;
    int n;
    while ((n = in.read(buf)) != -1) {
      total += n;
      if (total > maxBytes) {
        throw new ApiException(
            HttpStatus.CONTENT_TOO_LARGE,
            "too_large",
            "Файл больше " + (maxBytes / 1024 / 1024) + " МБ");
      }
      out.write(buf, 0, n);
    }
    return total;
  }

  public Optional<StoredFile> find(long id) {
    return db.sql("SELECT * FROM files WHERE id = ?").param(id).query(MAPPER).optional();
  }

  /** Расшифрованное содержимое. Вызывающий закрывает поток. */
  public InputStream open(StoredFile f) throws IOException {
    return FileCrypto.decrypt(Files.newInputStream(path(f.uuid())), key, f.uuid());
  }

  public void delete(StoredFile f) {
    try {
      Files.deleteIfExists(path(f.uuid()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    db.sql("DELETE FROM files WHERE id = ?").param(f.id()).update();
  }

  /** Файлы без ссылок (загружены, но не прикреплены) старше порога. */
  public int deleteOrphans(long olderThan) {
    List<StoredFile> orphans =
        db.sql(
                """
                SELECT * FROM files f WHERE f.created_at < ?
                  AND NOT EXISTS (SELECT 1 FROM materials m WHERE m.file_id = f.id)
                  AND NOT EXISTS (SELECT 1 FROM homework_attachments a WHERE a.file_id = f.id)
                  AND NOT EXISTS (SELECT 1 FROM post_attachments p WHERE p.file_id = f.id)
                """)
            .param(olderThan)
            .query(MAPPER)
            .list();
    for (StoredFile f : orphans) {
      delete(f);
    }
    if (!orphans.isEmpty()) {
      log.info("Удалено неприкреплённых файлов: {}", orphans.size());
    }
    return orphans.size();
  }

  /** Где лежит зашифрованное содержимое файла. */
  public Path path(String uuid) {
    if (!uuid.matches("[0-9a-f-]{36}")) {
      throw new IllegalArgumentException("bad uuid");
    }
    return root.resolve(uuid.substring(0, 2)).resolve(uuid);
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
