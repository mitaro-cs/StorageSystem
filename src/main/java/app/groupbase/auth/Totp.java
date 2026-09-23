package app.groupbase.auth;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP по RFC 6238: SHA-1, 6 цифр, шаг 30 секунд, допуск ±1 шаг. Часы телефона могут спешить или
 * отставать: при подключении допуск шире, найденный сдвиг запоминается и учитывается при входе.
 */
public final class Totp {

  public static final int STEP_SECONDS = 30;

  /** Обычный допуск: код соседнего шага (задержка ввода). */
  public static final int WINDOW = 1;

  /** Допуск при подключении и предел запоминаемого сдвига часов: ±5 минут. */
  public static final int MAX_DRIFT = 10;

  private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

  private Totp() {}

  public static byte[] newSecret() {
    return Tokens.randomBytes(20);
  }

  public static long step(long epochMillis) {
    return Math.floorDiv(epochMillis / 1000, STEP_SECONDS);
  }

  public static long verify(byte[] secret, String code, long nowMillis, Long lastUsedStep) {
    return verify(secret, code, nowMillis, lastUsedStep, 0, WINDOW);
  }

  /**
   * Номер шага, которому соответствует код, или -1. Подходят шаги в пределах ±{@code window} от
   * «времени телефона» (сейчас + {@code drift}) и в обычном окне ±1 от времени сервера — на случай,
   * если часы телефона уже поправили. Шаги не больше {@code lastUsedStep} отвергаются (код нельзя
   * использовать повторно).
   */
  public static long verify(
      byte[] secret, String code, long nowMillis, Long lastUsedStep, int drift, int window) {
    if (code == null) {
      return -1;
    }
    // Пробелы (в т.ч. неразрывные) и невидимые символы попадают при копировании из приложений.
    String digits = code.replaceAll("[\\s\\p{Z}\\p{Cf}-]", "");
    if (!digits.matches("\\d{6}")) {
      return -1;
    }
    long now = step(nowMillis);
    long phone = now + drift;
    for (long s = Math.min(now - WINDOW, phone - window);
        s <= Math.max(now + WINDOW, phone + window);
        s++) {
      boolean near = Math.abs(s - now) <= WINDOW || Math.abs(s - phone) <= window;
      if (!near || (lastUsedStep != null && s <= lastUsedStep)) {
        continue;
      }
      if (java.security.MessageDigest.isEqual(
          code(secret, s).getBytes(StandardCharsets.US_ASCII),
          digits.getBytes(StandardCharsets.US_ASCII))) {
        return s;
      }
    }
    return -1;
  }

  /** Сдвиг часов телефона для найденного шага, в пределах ±{@link #MAX_DRIFT}. */
  public static int drift(long matchedStep, long nowMillis) {
    long d = matchedStep - step(nowMillis);
    return (int) Math.max(-MAX_DRIFT, Math.min(MAX_DRIFT, d));
  }

  public static String code(byte[] secret, long step) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(secret, "HmacSHA1"));
      byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
      int off = h[h.length - 1] & 0x0f;
      int bin =
          ((h[off] & 0x7f) << 24)
              | ((h[off + 1] & 0xff) << 16)
              | ((h[off + 2] & 0xff) << 8)
              | (h[off + 3] & 0xff);
      return String.format("%06d", bin % 1_000_000);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String base32(byte[] data) {
    StringBuilder sb = new StringBuilder();
    int buffer = 0;
    int bits = 0;
    for (byte b : data) {
      buffer = (buffer << 8) | (b & 0xff);
      bits += 8;
      while (bits >= 5) {
        sb.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
        bits -= 5;
      }
    }
    if (bits > 0) {
      sb.append(BASE32.charAt((buffer << (5 - bits)) & 31));
    }
    return sb.toString();
  }

  /** Ссылка для приложения-аутентификатора (QR-код рисует фронт). */
  public static String uri(String issuer, String account, byte[] secret) {
    String label = enc(issuer) + ":" + enc(account);
    return "otpauth://totp/"
        + label
        + "?secret="
        + base32(secret)
        + "&issuer="
        + enc(issuer)
        + "&digits=6&period=30";
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
