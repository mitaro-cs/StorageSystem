package app.groupbase.avatars;

import app.groupbase.auth.Tokens;
import app.groupbase.config.GroupbaseProperties;
import app.groupbase.config.Secrets;
import app.groupbase.files.FileCrypto;
import app.groupbase.web.ApiException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.regex.Pattern;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Аватары пользователей, групп и предметов. Картинка перекодируется с нуля (квадрат по центру, 256
 * и 64 px, WebP), поэтому EXIF — геолокация, модель телефона — не сохраняется. Файлы на диске
 * зашифрованы, имя — случайный идентификатор (он же ключ кеша: {@code immutable}).
 */
@Service
public class AvatarService {

  public static final int MAX_BYTES = 5 * 1024 * 1024;
  private static final int MAX_SIDE = 8000;
  public static final int[] SIZES = {256, 64};
  private static final Pattern ID = Pattern.compile("[0-9a-f]{20}");
  private static final Pattern NAME = Pattern.compile("([0-9a-f]{20})-(256|64)\\.webp");

  private final Path dir;
  private final byte[] key;

  public AvatarService(GroupbaseProperties props, Secrets secrets) {
    this.dir = props.dataDir().resolve("avatars");
    this.key = secrets.filesKey();
  }

  /** Сохраняет аватар и возвращает его идентификатор. */
  public String store(InputStream body) throws IOException {
    byte[] data = body.readNBytes(MAX_BYTES + 1);
    if (data.length > MAX_BYTES) {
      throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "too_large", "Картинка больше 5 МБ");
    }
    if (!supported(data)) {
      throw new ApiException(
          HttpStatus.UNSUPPORTED_MEDIA_TYPE, "file_type", "Нужна картинка PNG, JPEG или WebP");
    }
    BufferedImage src = decode(data);
    BufferedImage square = cropSquare(src);
    String id = HexFormat.of().formatHex(Tokens.randomBytes(10));
    Files.createDirectories(dir);
    for (int size : SIZES) {
      byte[] webp = encodeWebp(resize(square, size));
      String name = id + "-" + size + ".webp";
      Path part = dir.resolve(name + ".part");
      try (OutputStream out = FileCrypto.encrypt(Files.newOutputStream(part), key, name)) {
        out.write(webp);
      }
      Files.move(part, dir.resolve(name), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
    return id;
  }

  /** Расшифрованный WebP по имени вида {@code <id>-64.webp}; null, если такого нет. */
  public InputStream open(String name) throws IOException {
    if (!NAME.matcher(name).matches()) {
      return null;
    }
    Path p = dir.resolve(name);
    if (!Files.exists(p)) {
      return null;
    }
    return FileCrypto.decrypt(Files.newInputStream(p), key, name);
  }

  public void delete(String id) {
    if (id == null || !ID.matcher(id).matches()) {
      return;
    }
    try {
      for (int size : SIZES) {
        Files.deleteIfExists(dir.resolve(id + "-" + size + ".webp"));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static boolean supported(byte[] d) {
    boolean png =
        d.length > 8 && (d[0] & 0xff) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G';
    boolean jpeg =
        d.length > 3 && (d[0] & 0xff) == 0xFF && (d[1] & 0xff) == 0xD8 && (d[2] & 0xff) == 0xFF;
    boolean webp =
        d.length > 12
            && d[0] == 'R'
            && d[1] == 'I'
            && d[2] == 'F'
            && d[3] == 'F'
            && d[8] == 'W'
            && d[9] == 'E'
            && d[10] == 'B'
            && d[11] == 'P';
    return png || jpeg || webp;
  }

  /** Декодирует, предварительно проверив размеры (защита от «бомб» — 20000×20000 в 50 КБ). */
  static BufferedImage decode(byte[] data) throws IOException {
    try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
      Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
      if (!readers.hasNext()) {
        throw bad();
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(in, true, true);
        int w = reader.getWidth(0);
        int h = reader.getHeight(0);
        if (w < 16 || h < 16 || w > MAX_SIDE || h > MAX_SIDE) {
          throw new ApiException(
              HttpStatus.BAD_REQUEST, "image_size", "Картинка должна быть от 16 до 8000 точек");
        }
        return reader.read(0);
      } catch (IOException | RuntimeException e) {
        if (e instanceof ApiException api) {
          throw api;
        }
        throw bad();
      } finally {
        reader.dispose();
      }
    }
  }

  private static ApiException bad() {
    return new ApiException(HttpStatus.BAD_REQUEST, "bad_image", "Не удалось прочитать картинку");
  }

  static BufferedImage cropSquare(BufferedImage src) {
    int side = Math.min(src.getWidth(), src.getHeight());
    int x = (src.getWidth() - side) / 2;
    int y = (src.getHeight() - side) / 2;
    BufferedImage out = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = out.createGraphics();
    g.setColor(java.awt.Color.WHITE);
    g.fillRect(0, 0, side, side);
    g.drawImage(src, -x, -y, null);
    g.dispose();
    return out;
  }

  /** Уменьшение ступенями по половине — без «лесенки» у бикубической интерполяции. */
  static BufferedImage resize(BufferedImage src, int size) {
    BufferedImage cur = src;
    while (cur.getWidth() / 2 >= size) {
      cur = scale(cur, cur.getWidth() / 2);
    }
    return cur.getWidth() == size ? cur : scale(cur, size);
  }

  private static BufferedImage scale(BufferedImage src, int size) {
    BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = out.createGraphics();
    g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.drawImage(src, 0, 0, size, size, null);
    g.dispose();
    return out;
  }

  static byte[] encodeWebp(BufferedImage img) throws IOException {
    Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType("image/webp");
    if (!writers.hasNext()) {
      throw new IOException("Кодировщик WebP недоступен на этой платформе");
    }
    ImageWriter writer = writers.next();
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (ImageOutputStream out = ImageIO.createImageOutputStream(bos)) {
      writer.setOutput(out);
      ImageWriteParam p = writer.getDefaultWriteParam();
      if (p.canWriteCompressed()) {
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        String[] types = p.getCompressionTypes();
        for (String t : types) {
          if (t.equalsIgnoreCase("lossy")) {
            p.setCompressionType(t);
          }
        }
        p.setCompressionQuality(0.85f);
      }
      writer.write(null, new IIOImage(img, null, null), p);
    } finally {
      writer.dispose();
    }
    return bos.toByteArray();
  }
}
