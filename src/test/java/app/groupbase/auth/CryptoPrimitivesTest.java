package app.groupbase.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CryptoPrimitivesTest {

  @Test
  void argon2idRoundTrip() {
    String h = Passwords.hash("correct horse battery");
    assertThat(h).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
    assertThat(Passwords.verify("correct horse battery", h)).isTrue();
    assertThat(Passwords.verify("correct horse batterY", h)).isFalse();
    assertThat(Passwords.verify(null, h)).isFalse();
    assertThat(Passwords.needsRehash(h)).isFalse();
    assertThat(Passwords.hash("same")).isNotEqualTo(Passwords.hash("same"));
  }

  @Test
  void passwordPolicy() {
    assertThat(Passwords.validate("short", "u")).isNotNull();
    assertThat(Passwords.validate("aaaaaaaaaaaa", "u")).isNotNull();
    assertThat(Passwords.validate("ivan.petrov", "ivan.petrov")).isNotNull();
    assertThat(Passwords.validate("good-password-42", "ivan")).isNull();
  }

  /** Тестовые векторы RFC 6238 (SHA-1), последние 6 цифр. */
  @Test
  void totpRfc6238Vectors() {
    byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    assertThat(Totp.code(secret, Totp.step(59_000))).isEqualTo("287082");
    assertThat(Totp.code(secret, Totp.step(1_111_111_109_000L))).isEqualTo("081804");
    assertThat(Totp.code(secret, Totp.step(1_234_567_890_000L))).isEqualTo("005924");
    assertThat(Totp.code(secret, Totp.step(20_000_000_000_000L))).isEqualTo("353130");
  }

  @Test
  void totpWindowAndReplay() {
    byte[] secret = Totp.newSecret();
    long now = 1_800_000_000_000L;
    long step = Totp.step(now);
    assertThat(Totp.verify(secret, Totp.code(secret, step), now, null)).isEqualTo(step);
    assertThat(Totp.verify(secret, Totp.code(secret, step - 1), now, null)).isEqualTo(step - 1);
    assertThat(Totp.verify(secret, Totp.code(secret, step + 2), now, null)).isEqualTo(-1);
    // Повтор уже использованного шага отвергается.
    assertThat(Totp.verify(secret, Totp.code(secret, step), now, step)).isEqualTo(-1);
    assertThat(Totp.verify(secret, "12345", now, null)).isEqualTo(-1);
    assertThat(Totp.verify(secret, null, now, null)).isEqualTo(-1);
    // Пробелы, в т.ч. неразрывные, из буфера обмена не мешают.
    String c = Totp.code(secret, step);
    assertThat(Totp.verify(secret, c.substring(0, 3) + "\u00a0" + c.substring(3), now, null))
        .isEqualTo(step);
  }

  @Test
  void totpClockDrift() {
    byte[] secret = Totp.newSecret();
    long now = 1_700_000_000_000L;
    long step = Totp.step(now);
    String ahead = Totp.code(secret, step + 3); // телефон спешит на 90 с
    assertThat(Totp.verify(secret, ahead, now, null)).isEqualTo(-1);
    // При подключении окно шире, сдвиг запоминается.
    assertThat(Totp.verify(secret, ahead, now, null, 0, Totp.MAX_DRIFT)).isEqualTo(step + 3);
    assertThat(Totp.drift(step + 3, now)).isEqualTo(3);
    assertThat(Totp.drift(step + 40, now)).isEqualTo(Totp.MAX_DRIFT);
    // При входе окно ±1 вокруг сдвига и ±1 вокруг времени сервера (часы могли поправить).
    assertThat(Totp.verify(secret, ahead, now, null, 3, Totp.WINDOW)).isEqualTo(step + 3);
    assertThat(Totp.verify(secret, Totp.code(secret, step), now, null, 3, Totp.WINDOW))
        .isEqualTo(step);
    assertThat(Totp.verify(secret, Totp.code(secret, step + 6), now, null, 3, Totp.WINDOW))
        .isEqualTo(-1);
    assertThat(Totp.verify(secret, Totp.code(secret, step - 11), now, null, 0, Totp.MAX_DRIFT))
        .isEqualTo(-1);
  }

  @Test
  void base32() {
    assertThat(Totp.base32("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
    assertThat(Totp.uri("group base", "ivan", new byte[] {1}))
        .startsWith("otpauth://totp/group%20base:ivan?secret=AE&issuer=group%20base");
  }

  @Test
  void tokens() {
    String t = Tokens.newToken();
    assertThat(t).hasSize(43);
    assertThat(Tokens.looksValid(t)).isTrue();
    assertThat(Tokens.looksValid(t + "=")).isFalse();
    assertThat(Tokens.looksValid("../../etc/passwd")).isFalse();
    assertThat(Tokens.sha256(t)).hasSize(32);
    assertThat(Tokens.temporaryPassword()).matches("[A-Za-z2-9]{4}-[A-Za-z2-9]{4}-[A-Za-z2-9]{4}");
  }
}
