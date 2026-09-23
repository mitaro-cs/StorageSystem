package app.groupbase.config;

import app.groupbase.auth.Tokens;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Секреты приложения. Берутся из переменных окружения ({@code GROUPBASE_APP_KEY}, {@code
 * GROUPBASE_FILES_KEY}, base64 32 байта), иначе генерируются при первом запуске и хранятся в {@code
 * <data>/secrets} с правами 0600. В БД и в groupbase.toml секретов нет.
 */
@Component
public class Secrets {

  public static final String APP_KEY = "app.key";
  public static final String FILES_KEY = "files.key";
  private static final int GCM_TAG_BITS = 128;
  private static final int NONCE_LEN = 12;

  private final byte[] appKey;
  private final byte[] filesKey;

  public Secrets(GroupbaseProperties props, Environment env) {
    Path dir = props.secretsDir();
    this.appKey = load(env, "GROUPBASE_APP_KEY", dir.resolve(APP_KEY));
    this.filesKey = load(env, "GROUPBASE_FILES_KEY", dir.resolve(FILES_KEY));
  }

  public byte[] filesKey() {
    return filesKey.clone();
  }

  /** Ключ для HMAC и производных значений. */
  public byte[] appKey() {
    return appKey.clone();
  }

  /** Шифрует небольшое значение ключом приложения (AES-256-GCM, nonce в начале). */
  public byte[] seal(byte[] plaintext, String context) {
    try {
      byte[] nonce = Tokens.randomBytes(NONCE_LEN);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(appKey, "AES"),
          new GCMParameterSpec(GCM_TAG_BITS, nonce));
      c.updateAAD(context.getBytes(StandardCharsets.UTF_8));
      byte[] ct = c.doFinal(plaintext);
      return ByteBuffer.allocate(NONCE_LEN + ct.length).put(nonce).put(ct).array();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public byte[] open(byte[] sealed, String context) {
    try {
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(appKey, "AES"),
          new GCMParameterSpec(GCM_TAG_BITS, sealed, 0, NONCE_LEN));
      c.updateAAD(context.getBytes(StandardCharsets.UTF_8));
      return c.doFinal(sealed, NONCE_LEN, sealed.length - NONCE_LEN);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Не удалось расшифровать значение: неверный ключ?", e);
    }
  }

  private static byte[] load(Environment env, String var, Path file) {
    String fromEnv = env.getProperty(var);
    if (fromEnv != null && !fromEnv.isBlank()) {
      return decodeKey(fromEnv.trim(), var);
    }
    try {
      if (Files.exists(file)) {
        return decodeKey(Files.readString(file).trim(), file.toString());
      }
      byte[] key = Tokens.randomBytes(32);
      writePrivate(file, Base64.getEncoder().encodeToString(key) + "\n");
      return key;
    } catch (IOException e) {
      throw new UncheckedIOException("Не удалось прочитать или создать секрет " + file, e);
    }
  }

  private static byte[] decodeKey(String b64, String source) {
    byte[] key = Base64.getDecoder().decode(b64);
    if (key.length != 32) {
      throw new IllegalStateException(source + ": ключ должен быть 32 байта в base64");
    }
    return key;
  }

  /** Создаёт файл, доступный только владельцу (0600; каталог 0700). */
  public static void writePrivate(Path file, String content) throws IOException {
    Path dir = file.toAbsolutePath().getParent();
    boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    if (!Files.exists(dir)) {
      Files.createDirectories(dir);
      if (posix) {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
      }
    }
    if (posix) {
      if (!Files.exists(file)) {
        Files.createFile(
            file,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      }
      Files.writeString(file, content, StandardOpenOption.TRUNCATE_EXISTING);
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    } else {
      Files.writeString(file, content);
    }
  }
}
