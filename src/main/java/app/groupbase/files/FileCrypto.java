package app.groupbase.files;

import app.groupbase.auth.Tokens;
import java.io.EOFException;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Потоковое шифрование файлов AES-256-GCM по чанкам (схема STREAM).
 *
 * <p>Формат: {@code "GBF1"} | обёрнутый ключ файла (12 байт nonce + 32 байта ключа + 16 байт тега)
 * | префикс nonce (7 байт) | чанки. Каждый чанк — до 64 КиБ открытого текста плюс тег GCM; nonce
 * чанка = префикс || номер (4 байта) || флаг последнего чанка. Последний чанк всегда есть (может
 * быть пустым), поэтому обрезка файла по границе чанка обнаруживается. AAD — UUID файла: чанк
 * нельзя переставить в другой файл. Ключ файла случайный и обёрнут мастер-ключом — мастер-ключ
 * можно сменить, перешифровав только заголовки.
 */
public final class FileCrypto {

  static final byte[] MAGIC = {'G', 'B', 'F', '1'};
  static final int CHUNK = 64 * 1024;
  static final int TAG = 16;
  private static final int WRAPPED = 12 + 32 + TAG;
  private static final int PREFIX = 7;
  public static final int HEADER = MAGIC.length + WRAPPED + PREFIX;

  private FileCrypto() {}

  /** Поток, который шифрует всё записанное в {@code out}. Обязательно закрыть. */
  public static OutputStream encrypt(OutputStream out, byte[] masterKey, String fileId)
      throws IOException {
    byte[] dek = Tokens.randomBytes(32);
    byte[] prefix = Tokens.randomBytes(PREFIX);
    byte[] aad = fileId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    out.write(MAGIC);
    out.write(wrap(masterKey, dek, aad));
    out.write(prefix);
    return new EncryptingStream(out, dek, prefix, aad);
  }

  /** Поток расшифрованных данных. Ошибка целостности — IOException. */
  public static InputStream decrypt(InputStream in, byte[] masterKey, String fileId)
      throws IOException {
    byte[] header = in.readNBytes(HEADER);
    if (header.length < HEADER || !Arrays.equals(Arrays.copyOf(header, 4), MAGIC)) {
      throw new IOException("Повреждённый или незашифрованный файл");
    }
    byte[] aad = fileId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] dek = unwrap(masterKey, Arrays.copyOfRange(header, 4, 4 + WRAPPED), aad);
    byte[] prefix = Arrays.copyOfRange(header, 4 + WRAPPED, HEADER);
    return new DecryptingStream(in, dek, prefix, aad);
  }

  /** Размер зашифрованного файла по размеру исходного. */
  public static long encryptedSize(long plain) {
    long full = plain / CHUNK;
    return HEADER + full * (CHUNK + TAG) + (plain % CHUNK) + TAG;
  }

  private static byte[] wrap(byte[] master, byte[] dek, byte[] aad) {
    try {
      byte[] nonce = Tokens.randomBytes(12);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.ENCRYPT_MODE, new SecretKeySpec(master, "AES"), new GCMParameterSpec(128, nonce));
      c.updateAAD(aad);
      byte[] ct = c.doFinal(dek);
      return ByteBuffer.allocate(WRAPPED).put(nonce).put(ct).array();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] unwrap(byte[] master, byte[] wrapped, byte[] aad) throws IOException {
    try {
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(master, "AES"),
          new GCMParameterSpec(128, wrapped, 0, 12));
      c.updateAAD(aad);
      return c.doFinal(wrapped, 12, wrapped.length - 12);
    } catch (GeneralSecurityException e) {
      throw new IOException("Не удалось расшифровать ключ файла: неверный GROUPBASE_FILES_KEY?", e);
    }
  }

  static byte[] nonce(byte[] prefix, long counter, boolean last) {
    if (counter > 0xFFFF_FFFFL) {
      throw new IllegalStateException("Файл слишком большой");
    }
    return ByteBuffer.allocate(12)
        .put(prefix)
        .putInt((int) counter)
        .put((byte) (last ? 1 : 0))
        .array();
  }

  private static Cipher cipher(int mode, byte[] dek, byte[] nonce, byte[] aad) {
    try {
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(mode, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(128, nonce));
      c.updateAAD(aad);
      return c;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static final class EncryptingStream extends FilterOutputStream {
    private final byte[] dek;
    private final byte[] prefix;
    private final byte[] aad;
    private final byte[] buf = new byte[CHUNK];
    private int len;
    private long counter;
    private boolean closed;

    EncryptingStream(OutputStream out, byte[] dek, byte[] prefix, byte[] aad) {
      super(out);
      this.dek = dek;
      this.prefix = prefix;
      this.aad = aad;
    }

    @Override
    public void write(int b) throws IOException {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int n) throws IOException {
      while (n > 0) {
        if (len == CHUNK) {
          flushChunk(false);
        }
        int take = Math.min(n, CHUNK - len);
        System.arraycopy(b, off, buf, len, take);
        len += take;
        off += take;
        n -= take;
      }
    }

    private void flushChunk(boolean last) throws IOException {
      try {
        byte[] ct =
            cipher(Cipher.ENCRYPT_MODE, dek, nonce(prefix, counter++, last), aad)
                .doFinal(buf, 0, len);
        out.write(ct);
        len = 0;
      } catch (GeneralSecurityException e) {
        throw new IOException(e);
      }
    }

    @Override
    public void close() throws IOException {
      if (closed) {
        return;
      }
      closed = true;
      if (len == CHUNK) {
        // Полный чанк не может быть последним: иначе расшифровщик не отличит его от обрезки.
        flushChunk(false);
      }
      flushChunk(true);
      Arrays.fill(buf, (byte) 0);
      out.close();
    }
  }

  private static final class DecryptingStream extends InputStream {
    private final InputStream in;
    private final byte[] dek;
    private final byte[] prefix;
    private final byte[] aad;
    private byte[] plain = new byte[0];
    private int pos;
    private long counter;
    private boolean finished;

    DecryptingStream(InputStream in, byte[] dek, byte[] prefix, byte[] aad) {
      this.in = in;
      this.dek = dek;
      this.prefix = prefix;
      this.aad = aad;
    }

    private boolean fill() throws IOException {
      while (pos >= plain.length) {
        if (finished) {
          return false;
        }
        byte[] ct = in.readNBytes(CHUNK + TAG);
        boolean last = ct.length < CHUNK + TAG;
        if (ct.length < TAG) {
          throw new EOFException("Файл обрезан");
        }
        try {
          plain = cipher(Cipher.DECRYPT_MODE, dek, nonce(prefix, counter++, last), aad).doFinal(ct);
        } catch (GeneralSecurityException e) {
          throw new IOException("Файл повреждён или подменён", e);
        }
        pos = 0;
        if (last) {
          finished = true;
          if (in.read() != -1) {
            throw new IOException("Лишние данные после конца файла");
          }
        }
      }
      return true;
    }

    @Override
    public int read() throws IOException {
      return fill() ? plain[pos++] & 0xff : -1;
    }

    @Override
    public int read(byte[] b, int off, int n) throws IOException {
      if (n == 0) {
        return 0;
      }
      if (!fill()) {
        return -1;
      }
      int take = Math.min(n, plain.length - pos);
      System.arraycopy(plain, pos, b, off, take);
      pos += take;
      return take;
    }

    @Override
    public void close() throws IOException {
      in.close();
    }
  }
}
