package app.groupbase.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.groupbase.auth.Tokens;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileCryptoTest {

  private static final byte[] KEY = Tokens.randomBytes(32);
  private static final String ID = "3f2b8c1e-0000-4000-8000-000000000001";

  private static byte[] encrypt(byte[] plain, byte[] key, String id) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (OutputStream out = FileCrypto.encrypt(bos, key, id)) {
      // Пишем кусками разного размера, чтобы проверить буферизацию по чанкам.
      int pos = 0;
      int step = 1;
      while (pos < plain.length) {
        int n = Math.min(step, plain.length - pos);
        out.write(plain, pos, n);
        pos += n;
        step = step * 3 + 7;
      }
    }
    return bos.toByteArray();
  }

  private static byte[] decrypt(byte[] enc, byte[] key, String id) throws IOException {
    try (var in = FileCrypto.decrypt(new ByteArrayInputStream(enc), key, id)) {
      return in.readAllBytes();
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 65_535, 65_536, 65_537, 3 * 65_536 + 17})
  void roundTrip(int size) throws IOException {
    byte[] plain = Tokens.randomBytes(size);
    byte[] enc = encrypt(plain, KEY, ID);
    assertThat(enc.length).isEqualTo(FileCrypto.encryptedSize(size));
    assertThat(decrypt(enc, KEY, ID)).isEqualTo(plain);
  }

  @Test
  void ciphertextDoesNotContainPlaintext() throws IOException {
    byte[] plain = "Секретный конспект лекции".repeat(100).getBytes();
    byte[] enc = encrypt(plain, KEY, ID);
    assertThat(new String(enc, java.nio.charset.StandardCharsets.ISO_8859_1))
        .doesNotContain("Секрет");
  }

  @Test
  void tamperingIsDetected() throws IOException {
    byte[] enc = encrypt(Tokens.randomBytes(100_000), KEY, ID);
    enc[FileCrypto.HEADER + 10] ^= 1;
    assertThatThrownBy(() -> decrypt(enc, KEY, ID)).isInstanceOf(IOException.class);
  }

  @Test
  void truncationAtChunkBoundaryIsDetected() throws IOException {
    byte[] enc = encrypt(Tokens.randomBytes(2 * 65_536), KEY, ID);
    // Отрезаем последний (пустой) чанк — остаются только полные чанки.
    byte[] cut = Arrays.copyOf(enc, enc.length - 16);
    assertThatThrownBy(() -> decrypt(cut, KEY, ID)).isInstanceOf(IOException.class);
    byte[] midCut = Arrays.copyOf(enc, enc.length - 16 - 100);
    assertThatThrownBy(() -> decrypt(midCut, KEY, ID)).isInstanceOf(IOException.class);
  }

  @Test
  void wrongKeyOrFileIdFails() throws IOException {
    byte[] enc = encrypt(Tokens.randomBytes(1000), KEY, ID);
    assertThatThrownBy(() -> decrypt(enc, Tokens.randomBytes(32), ID))
        .isInstanceOf(IOException.class);
    assertThatThrownBy(() -> decrypt(enc, KEY, "3f2b8c1e-0000-4000-8000-000000000002"))
        .isInstanceOf(IOException.class);
  }

  @Test
  void rejectsPlainFiles() {
    assertThatThrownBy(() -> decrypt("not encrypted at all, surely".repeat(10).getBytes(), KEY, ID))
        .isInstanceOf(IOException.class);
  }
}
