package app.groupbase.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** Случайные токены (256 бит, base64url) и их SHA-256 для хранения в БД. */
public final class Tokens {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  private Tokens() {}

  public static byte[] randomBytes(int n) {
    byte[] b = new byte[n];
    RANDOM.nextBytes(b);
    return b;
  }

  /** Новый токен: 32 случайных байта в base64url (43 символа). */
  public static String newToken() {
    return B64.encodeToString(randomBytes(32));
  }

  public static byte[] sha256(String token) {
    return sha256(token.getBytes(StandardCharsets.UTF_8));
  }

  public static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Токен похож на наш: только base64url и разумная длина. Отсекает мусор до похода в БД. */
  public static boolean looksValid(String token) {
    return token != null && token.length() == 43 && token.chars().allMatch(Tokens::isB64Url);
  }

  private static boolean isB64Url(int c) {
    return (c >= 'A' && c <= 'Z')
        || (c >= 'a' && c <= 'z')
        || (c >= '0' && c <= '9')
        || c == '-'
        || c == '_';
  }

  /** Читаемый временный пароль без похожих символов (0/O, 1/l/I). */
  public static String temporaryPassword() {
    String alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 14; i++) {
      if (i > 0 && i % 5 == 4) {
        sb.append('-');
      } else {
        sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
      }
    }
    return sb.toString();
  }
}
