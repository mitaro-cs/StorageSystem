package app.groupbase.notify;

import app.groupbase.accounts.PublicUrl;
import app.groupbase.config.GroupbaseProperties;
import app.groupbase.config.Secrets;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.ECPrivateKey;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  /** Контакт по умолчанию, когда у сайта нет своего адреса https: страница проекта. */
  static final String PROJECT = "https://github.com/mitaro-cs/StorageSystem";

  private static final Logger log = LoggerFactory.getLogger(VapidKeys.class);

  /** Домен, которого нет в интернете: localhost, IP-адрес, .local и подобные, без точки. */
  private static final Pattern LOCAL_DOMAIN =
      Pattern.compile(
          "^(localhost|[\\d.]+|\\[.*]|[^.]+|.*\\.(local|localhost|lan|home|internal|test|invalid))$");

  private final ECPrivateKey privateKey;
  private final String publicKey;
  private final String configured;
  private final PublicUrl site;

  public VapidKeys(GroupbaseProperties props, Environment env, PublicUrl site) {
    byte[] d = load(env.getProperty("GROUPBASE_VAPID_KEY"), props.secretsDir().resolve(FILE));
    this.privateKey = PushCrypto.privateKey(d);
    this.publicKey = PushCrypto.b64(PushCrypto.publicFromPrivate(d));
    this.site = site;
    String s = props.push().subject();
    this.configured = s == null ? "" : s.strip();
    if (!configured.isEmpty() && !usable(configured)) {
      log.warn(
          "Контакт для служб push (push.subject) не подходит: нужен mailto: с настоящим доменом"
              + " или https:// — используется адрес сайта");
    }
  }

  /** Открытый ключ для браузера (applicationServerKey), base64url. */
  public String publicKey() {
    return publicKey;
  }

  ECPrivateKey privateKey() {
    return privateKey;
  }

  /** Контакт для служб push сейчас: адрес сайта может смениться (туннель), поэтому не кешируем. */
  String subject() {
    return subject(configured, site.get().orElse(""));
  }

  /**
   * Контакт для служб push (claim {@code sub} в VAPID): из настроек, иначе адрес сайта https, иначе
   * страница проекта. Служба Apple отвечает 403 BadJwtToken на {@code mailto:} с адресом без
   * настоящего домена (admin@localhost, admin@127.0.0.1) — такие не отправляем никогда.
   */
  static String subject(String configured, String siteUrl) {
    if (usable(configured)) {
      return configured.strip();
    }
    String url = siteUrl == null ? "" : siteUrl.strip().replaceAll("/+$", "");
    if (url.startsWith("https://") && host(url) != null) {
      return url;
    }
    return PROJECT;
  }

  /** {@code mailto:} с доменом из интернета или {@code https://} с адресом. */
  static boolean usable(String subject) {
    if (subject == null || subject.isBlank()) {
      return false;
    }
    String s = subject.strip();
    if (s.startsWith("https://")) {
      return host(s) != null;
    }
    if (!s.toLowerCase(Locale.ROOT).startsWith("mailto:")) {
      return false;
    }
    String address = s.substring("mailto:".length());
    int at = address.lastIndexOf('@');
    if (at <= 0 || address.contains(" ")) {
      return false;
    }
    String domain = address.substring(at + 1).toLowerCase(Locale.ROOT);
    return !domain.isEmpty() && !LOCAL_DOMAIN.matcher(domain).matches();
  }

  private static String host(String url) {
    try {
      return URI.create(url).getHost();
    } catch (IllegalArgumentException e) {
      return null;
    }
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
