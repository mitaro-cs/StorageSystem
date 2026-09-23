package app.groupbase.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Argon2id в формате PHC: {@code $argon2id$v=19$m=19456,t=2,p=1$соль$хеш}. Параметры — минимум
 * OWASP, чтобы вход не тормозил на слабом домашнем сервере.
 */
public final class Passwords {

  public static final int MIN_LENGTH = 10;
  public static final int MAX_LENGTH = 256;

  private static final int MEMORY_KIB = 19456;
  private static final int ITERATIONS = 2;
  private static final int PARALLELISM = 1;
  private static final int SALT_LEN = 16;
  private static final int HASH_LEN = 32;
  private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();
  private static final Base64.Decoder B64D = Base64.getDecoder();

  /** Хеш для сравнения, когда пользователя нет: время ответа не выдаёт, существует ли имя. */
  private static final String DUMMY = hash("dummy-password-for-timing");

  private Passwords() {}

  public static String hash(String password) {
    byte[] salt = Tokens.randomBytes(SALT_LEN);
    byte[] out = derive(password, salt, MEMORY_KIB, ITERATIONS, PARALLELISM, HASH_LEN);
    return "$argon2id$v=19$m=%d,t=%d,p=%d$%s$%s"
        .formatted(
            MEMORY_KIB, ITERATIONS, PARALLELISM, B64.encodeToString(salt), B64.encodeToString(out));
  }

  public static boolean verify(String password, String encoded) {
    if (password == null || encoded == null) {
      verify("x", DUMMY);
      return false;
    }
    String[] parts = encoded.split("\\$");
    // ["", "argon2id", "v=19", "m=..,t=..,p=..", salt, hash]
    if (parts.length != 6 || !parts[1].equals("argon2id")) {
      return false;
    }
    int m = 0;
    int t = 0;
    int p = 0;
    for (String kv : parts[3].split(",")) {
      String[] pair = kv.split("=");
      int v = Integer.parseInt(pair[1]);
      switch (pair[0]) {
        case "m" -> m = v;
        case "t" -> t = v;
        case "p" -> p = v;
        default -> {
          return false;
        }
      }
    }
    byte[] salt = B64D.decode(parts[4]);
    byte[] expected = B64D.decode(parts[5]);
    byte[] actual = derive(password, salt, m, t, p, expected.length);
    return MessageDigest.isEqual(expected, actual);
  }

  /** Сравнение с фиктивным хешем, чтобы выровнять время ответа. */
  public static void burnTime() {
    verify("x", DUMMY);
  }

  /** Нужно ли перехешировать (параметры устарели). */
  public static boolean needsRehash(String encoded) {
    return encoded == null
        || !encoded.startsWith(
            "$argon2id$v=19$m=%d,t=%d,p=%d$".formatted(MEMORY_KIB, ITERATIONS, PARALLELISM));
  }

  /** Сообщение об ошибке или null, если пароль подходит. */
  public static String validate(String password, String username) {
    if (password == null || password.length() < MIN_LENGTH) {
      return "Пароль должен быть не короче " + MIN_LENGTH + " символов";
    }
    if (password.length() > MAX_LENGTH) {
      return "Пароль слишком длинный";
    }
    if (username != null && password.equalsIgnoreCase(username)) {
      return "Пароль не должен совпадать с именем пользователя";
    }
    if (password.chars().distinct().count() < 4) {
      return "Пароль слишком простой";
    }
    return null;
  }

  private static byte[] derive(String password, byte[] salt, int m, int t, int p, int len) {
    Argon2Parameters params =
        new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(m)
            .withIterations(t)
            .withParallelism(p)
            .withSalt(salt)
            .build();
    Argon2BytesGenerator gen = new Argon2BytesGenerator();
    gen.init(params);
    byte[] out = new byte[len];
    gen.generateBytes(password.getBytes(StandardCharsets.UTF_8), out);
    return out;
  }
}
