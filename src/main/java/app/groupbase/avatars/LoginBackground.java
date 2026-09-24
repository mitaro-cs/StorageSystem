package app.groupbase.avatars;

import app.groupbase.accounts.InstanceSettings;
import app.groupbase.auth.Tokens;
import app.groupbase.config.GroupbaseProperties;
import app.groupbase.config.Secrets;
import app.groupbase.files.FileCrypto;
import app.groupbase.web.ApiException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Фон страниц входа и регистрации: один из встроенных рисунков интерфейса или своя картинка
 * администратора. Картинка, как и аватары, перекодируется с нуля (без EXIF), уменьшается до 1920
 * точек по длинной стороне и хранится зашифрованной рядом с аватарами — поэтому попадает в
 * резервные копии.
 *
 * <p>Значение настройки: {@code preset:<имя>}, {@code image:<id>} или {@code none}.
 */
@Service
public class LoginBackground {

  public static final String SETTING = "appearance.login_background";
  public static final String DEFAULT = "preset:aurora";
  public static final Set<String> PRESETS = Set.of("aurora", "paper", "night", "notebook");
  public static final int MAX_BYTES = 10 * 1024 * 1024;
  static final int MAX_SIDE = 1920;

  private static final Pattern ID = Pattern.compile("[0-9a-f]{20}");

  private final Path dir;
  private final byte[] key;
  private final InstanceSettings settings;

  public LoginBackground(GroupbaseProperties props, Secrets secrets, InstanceSettings settings) {
    this.dir = props.dataDir().resolve("avatars");
    this.key = secrets.filesKey();
    this.settings = settings;
  }

  /** Текущий фон: {@code preset:…}, {@code image:…} или {@code none}. */
  public String current() {
    String v = settings.get(SETTING).orElse(DEFAULT);
    return valid(v) ? v : DEFAULT;
  }

  /** Выбрать встроенный рисунок или «без фона»; своя картинка при этом удаляется. */
  public String choose(String value) {
    String v = value == null ? "" : value.strip();
    if (!v.equals("none") && !(v.startsWith("preset:") && PRESETS.contains(v.substring(7)))) {
      throw ApiException.invalid("loginBackground", "Такого фона нет");
    }
    replace(v);
    return v;
  }

  /** Своя картинка администратора. */
  public String upload(InputStream body) throws IOException {
    byte[] data = body.readNBytes(MAX_BYTES + 1);
    if (data.length > MAX_BYTES) {
      throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "too_large", "Картинка больше 10 МБ");
    }
    if (!AvatarService.supported(data)) {
      throw new ApiException(
          HttpStatus.UNSUPPORTED_MEDIA_TYPE, "file_type", "Нужна картинка PNG, JPEG или WebP");
    }
    byte[] webp = AvatarService.encodeWebp(fit(AvatarService.decode(data), MAX_SIDE));
    String id = HexFormat.of().formatHex(Tokens.randomBytes(10));
    Files.createDirectories(dir);
    String name = fileName(id);
    Path part = dir.resolve(name + ".part");
    try (OutputStream out = FileCrypto.encrypt(Files.newOutputStream(part), key, name)) {
      out.write(webp);
    }
    Files.move(part, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE);
    String value = "image:" + id;
    replace(value);
    return value;
  }

  /** Расшифрованная картинка по идентификатору; null, если её нет. */
  public InputStream open(String id) throws IOException {
    if (id == null || !ID.matcher(id).matches()) {
      return null;
    }
    Path p = dir.resolve(fileName(id));
    if (!Files.exists(p)) {
      return null;
    }
    return FileCrypto.decrypt(Files.newInputStream(p), key, fileName(id));
  }

  private void replace(String value) {
    String old = current();
    settings.set(SETTING, value);
    if (old.startsWith("image:") && !old.equals(value)) {
      try {
        Files.deleteIfExists(dir.resolve(fileName(old.substring(6))));
      } catch (IOException e) {
        // останется лишний файл — не страшно
      }
    }
  }

  static boolean valid(String v) {
    return v.equals("none")
        || (v.startsWith("preset:") && PRESETS.contains(v.substring(7)))
        || (v.startsWith("image:") && ID.matcher(v.substring(6)).matches());
  }

  private static String fileName(String id) {
    return "background-" + id + ".webp";
  }

  /** Вписать в квадрат maxSide×maxSide с сохранением пропорций (меньшие не увеличиваются). */
  static BufferedImage fit(BufferedImage src, int maxSide) {
    int w = src.getWidth();
    int h = src.getHeight();
    double k = Math.min(1.0, (double) maxSide / Math.max(w, h));
    int tw = Math.max(1, (int) Math.round(w * k));
    int th = Math.max(1, (int) Math.round(h * k));
    BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = out.createGraphics();
    g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    g.setColor(java.awt.Color.WHITE);
    g.fillRect(0, 0, tw, th);
    g.drawImage(src, 0, 0, tw, th, null);
    g.dispose();
    return out;
  }
}
