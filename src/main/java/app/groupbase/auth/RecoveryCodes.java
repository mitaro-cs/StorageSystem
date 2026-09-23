package app.groupbase.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Резервные коды на случай потери телефона: 10 одноразовых кодов вида {@code abcd-efgh}. Алфавит
 * без похожих символов (0/o, 1/l/i), около 40 бит на код; попытки ограничены так же, как для TOTP.
 */
@Service
public class RecoveryCodes {

  public static final int COUNT = 10;
  private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final JdbcClient db;
  private final Clock clock;

  public RecoveryCodes(JdbcClient db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  /** Новый набор взамен старого; коды показываются один раз. */
  @Transactional
  public List<String> issue(long userId) {
    db.sql("DELETE FROM totp_recovery_codes WHERE user_id = ?").param(userId).update();
    List<String> codes = new ArrayList<>();
    for (int i = 0; i < COUNT; i++) {
      StringBuilder c = new StringBuilder();
      for (int j = 0; j < 8; j++) {
        c.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
      }
      String code = c.substring(0, 4) + "-" + c.substring(4);
      codes.add(code);
      db.sql("INSERT INTO totp_recovery_codes (user_id, code_hash) VALUES (?, ?)")
          .params(userId, hash(normalize(code)))
          .update();
    }
    return codes;
  }

  /** Гасит код; false, если такого неиспользованного кода нет. */
  public boolean use(long userId, String input) {
    String n = normalize(input);
    if (n.length() != 8) {
      return false;
    }
    return db.sql(
                "UPDATE totp_recovery_codes SET used_at = ?"
                    + " WHERE user_id = ? AND code_hash = ? AND used_at IS NULL")
            .params(clock.millis(), userId, hash(n))
            .update()
        == 1;
  }

  public int remaining(long userId) {
    return db.sql("SELECT count(*) FROM totp_recovery_codes WHERE user_id = ? AND used_at IS NULL")
        .param(userId)
        .query(Integer.class)
        .single();
  }

  /** Код из приложения — 6 цифр; всё, где есть буквы, считаем резервным кодом. */
  public static boolean looksLikeRecovery(String input) {
    return input != null && normalize(input).chars().anyMatch(Character::isLetter);
  }

  static String normalize(String input) {
    return input == null ? "" : input.toLowerCase(Locale.ROOT).replaceAll("[\\s\\-]", "");
  }

  private static String hash(String normalized) {
    return HexFormat.of().formatHex(Tokens.sha256(normalized));
  }
}
