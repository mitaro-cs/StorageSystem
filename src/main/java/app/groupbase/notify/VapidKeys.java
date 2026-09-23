package app.groupbase.notify;

import app.groupbase.config.GroupbaseProperties;
import app.groupbase.config.Secrets;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.ECPrivateKey;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Ключ VAPID, которым сервер подписывает запросы к службам push. Берётся из {@code
 * GROUPBASE_VAPID_KEY} (закрытый ключ P-256, base64url 32 байта) или генерируется в {@code
 * <data>/secrets/vapid.key}. Смена ключа отписывает все устройства — его нужно бэкапить вместе с
 * данными.
 */
@Component
public class VapidKeys {

  static final String FILE = "vapid.key";

  private final ECPrivateKey privateKey;
  private final String publicKey;
  private final String subject;

  public VapidKeys(GroupbaseProperties props, Environment env) {
    byte[] d = load(env.getProperty("GROUPBASE_VAPID_KEY"), props.secretsDir().resolve(FILE));
    this.privateKey = PushCrypto.privateKey(d);
    this.publicKey = PushCrypto.b64(PushCrypto.publicFromPrivate(d));
    this.subject = subject(props);
  }

  /** Открытый ключ для браузера (applicationServerKey), base64url. */
  public String publicKey() {
    return publicKey;
  }

  ECPrivateKey privateKey() {
    return privateKey;
  }

  String subject() {
    return subject;
  }

  /** Контакт для служб push: из настроек, иначе адрес сайта, иначе заглушка. */
  static String subject(GroupbaseProperties props) {
    String s = props.push().subject();
    if (s != null && !s.isBlank()) {
      return s.strip();
    }
    String base = props.baseUrl();
    if (base != null && base.startsWith("https://")) {
      return base;
    }
    String host = "localhost";
    try {
      if (base != null && !base.isBlank() && URI.create(base).getHost() != null) {
        host = URI.create(base).getHost();
      }
    } catch (IllegalArgumentException e) {
      // адрес сайта задан с ошибкой — остаётся заглушка
    }
    return "mailto:admin@" + host;
  }

  private static byte[] load(String fromEnv, Path file) {
    try {
      if (fromEnv != null && !fromEnv.isBlank()) {
        return check(PushCrypto.unb64(fromEnv));
      }
      if (Files.exists(file)) {
        return check(PushCrypto.unb64(Files.readString(file)));
      }
      byte[] d = PushCrypto.scalar((ECPrivateKey) PushCrypto.newKeyPair().getPrivate());
      Secrets.writePrivate(file, PushCrypto.b64(d) + "\n");
      return d;
    } catch (IOException e) {
      throw new UncheckedIOException("Не удалось прочитать или создать ключ VAPID " + file, e);
    }
  }

  private static byte[] check(byte[] d) {
    if (d.length != 32) {
      throw new IllegalStateException("Ключ VAPID должен быть 32 байта в base64url");
    }
    return d;
  }
}
