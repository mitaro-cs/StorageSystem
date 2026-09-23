package app.groupbase.notify;

import app.groupbase.auth.Tokens;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Шифрование Web Push (RFC 8291, aes128gcm, одна запись) и подпись VAPID (RFC 8292, ES256). Только
 * стандартная криптография JDK; BouncyCastle — лишь чтобы вывести открытый ключ из закрытого.
 */
public final class PushCrypto {

  static final int RECORD_SIZE = 4096;

  /** Запас под заголовок, тег и разделитель: служба push принимает тело до 4096 байт. */
  public static final int MAX_PLAINTEXT = 3000;

  static final ECParameterSpec P256 = p256();
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder B64D = Base64.getUrlDecoder();

  private PushCrypto() {}

  private static ECParameterSpec p256() {
    try {
      AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
      ap.init(new ECGenParameterSpec("secp256r1"));
      return ap.getParameterSpec(ECParameterSpec.class);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String b64(byte[] b) {
    return B64.encodeToString(b);
  }

  public static byte[] unb64(String s) {
    return B64D.decode(s.trim().replace('+', '-').replace('/', '_').replace("=", ""));
  }

  public static KeyPair newKeyPair() {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
      g.initialize(new ECGenParameterSpec("secp256r1"));
      return g.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Открытый ключ в несжатом виде: 0x04 || X || Y (65 байт). */
  public static byte[] encode(ECPublicKey key) {
    byte[] out = new byte[65];
    out[0] = 4;
    put32(key.getW().getAffineX(), out, 1);
    put32(key.getW().getAffineY(), out, 33);
    return out;
  }

  public static byte[] scalar(ECPrivateKey key) {
    byte[] out = new byte[32];
    put32(key.getS(), out, 0);
    return out;
  }

  private static void put32(BigInteger v, byte[] out, int at) {
    byte[] b = v.toByteArray();
    int len = Math.min(b.length, 32);
    System.arraycopy(b, b.length - len, out, at + 32 - len, len);
  }

  /** Разбирает ключ устройства и проверяет, что точка лежит на кривой P-256. */
  public static ECPublicKey publicKey(byte[] uncompressed) {
    if (uncompressed == null || uncompressed.length != 65 || uncompressed[0] != 4) {
      throw new IllegalArgumentException("Ключ должен быть точкой P-256 в несжатом виде");
    }
    BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
    BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
    var curve = P256.getCurve();
    BigInteger p = ((java.security.spec.ECFieldFp) curve.getField()).getP();
    BigInteger rhs = x.pow(3).add(curve.getA().multiply(x)).add(curve.getB()).mod(p);
    if (x.compareTo(p) >= 0 || y.compareTo(p) >= 0 || !y.pow(2).mod(p).equals(rhs)) {
      throw new IllegalArgumentException("Точка не на кривой P-256");
    }
    try {
      return (ECPublicKey)
          KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException(e);
    }
  }

  public static ECPrivateKey privateKey(byte[] scalar) {
    try {
      return (ECPrivateKey)
          KeyFactory.getInstance("EC")
              .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), P256));
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException(e);
    }
  }

  /** Открытый ключ для закрытого: Q = d·G. */
  public static byte[] publicFromPrivate(byte[] scalar) {
    var spec = org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("secp256r1");
    var q = spec.getG().multiply(new BigInteger(1, scalar)).normalize();
    return q.getEncoded(false);
  }

  /** Тело запроса к службе push для устройства с ключами p256dh и auth. */
  public static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret) {
    return encrypt(plaintext, uaPublic, authSecret, newKeyPair(), Tokens.randomBytes(16));
  }

  static byte[] encrypt(
      byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair as, byte[] salt) {
    if (plaintext.length > MAX_PLAINTEXT) {
      throw new IllegalArgumentException("Слишком длинное уведомление");
    }
    try {
      byte[] asPublic = encode((ECPublicKey) as.getPublic());
      KeyAgreement ka = KeyAgreement.getInstance("ECDH");
      ka.init(as.getPrivate());
      ka.doPhase(publicKey(uaPublic), true);
      byte[] ecdh = ka.generateSecret();

      byte[] prkKey = hmac(authSecret, ecdh);
      byte[] keyInfo = concat(ascii("WebPush: info"), new byte[] {0}, uaPublic, asPublic);
      byte[] ikm = hmac(prkKey, concat(keyInfo, new byte[] {1}));
      byte[] prk = hmac(salt, ikm);
      byte[] cek =
          Arrays.copyOf(
              hmac(prk, concat(ascii("Content-Encoding: aes128gcm"), new byte[] {0, 1})), 16);
      byte[] nonce =
          Arrays.copyOf(hmac(prk, concat(ascii("Content-Encoding: nonce"), new byte[] {0, 1})), 12);

      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
      // 0x02 — разделитель последней (и единственной) записи.
      byte[] ct = c.doFinal(concat(plaintext, new byte[] {2}));

      ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length);
      header.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic);
      return concat(header.array(), ct);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** JWT для заголовка Authorization: vapid t=…, k=…. */
  public static String vapidJwt(String audience, String subject, long expSeconds, PrivateKey key) {
    String header = b64(ascii("{\"typ\":\"JWT\",\"alg\":\"ES256\"}"));
    String claims =
        b64(
            ("{\"aud\":\""
                    + json(audience)
                    + "\",\"exp\":"
                    + expSeconds
                    + ",\"sub\":\""
                    + json(subject)
                    + "\"}")
                .getBytes(StandardCharsets.UTF_8));
    String unsigned = header + "." + claims;
    try {
      Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
      s.initSign(key);
      s.update(ascii(unsigned));
      return unsigned + "." + b64(s.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String json(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(data);
  }

  private static byte[] ascii(String s) {
    return s.getBytes(StandardCharsets.US_ASCII);
  }

  static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] p : parts) {
      out.writeBytes(p);
    }
    return out.toByteArray();
  }
}
