package app.groupbase.auth;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Проверки WebAuthn без сторонних библиотек: разбор clientDataJSON и authenticatorData, проверка
 * подписи ключом из SubjectPublicKeyInfo (его отдаёт сам браузер — {@code getPublicKey()}), так что
 * CBOR разбирать не нужно. Аттестация не проверяется («none»): ключ добавляет человек, уже вошедший
 * по паролю.
 */
public final class WebAuthn {

  /** COSE-алгоритмы, которые принимаем. */
  public static final int ES256 = -7;

  public static final int EDDSA = -8;
  public static final int RS256 = -257;

  static final int FLAG_UP = 0x01;
  static final int FLAG_UV = 0x04;
  static final int FLAG_AT = 0x40;

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Base64.Encoder B64U = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder B64U_DEC = Base64.getUrlDecoder();

  private WebAuthn() {}

  /** Неверные данные от браузера или ключа: причина — для журнала разработчика, не для людей. */
  public static final class Invalid extends Exception {
    private static final long serialVersionUID = 1L;

    Invalid(String reason) {
      super(reason, null, false, false);
    }
  }

  public record ClientData(String type, String challenge, String origin, boolean crossOrigin) {}

  /**
   * @param credentialId id ключа из attested credential data; есть только при регистрации
   */
  public record AuthData(byte[] rpIdHash, int flags, long signCount, byte[] credentialId) {
    public boolean userPresent() {
      return (flags & FLAG_UP) != 0;
    }

    public boolean userVerified() {
      return (flags & FLAG_UV) != 0;
    }
  }

  public static String b64u(byte[] data) {
    return B64U.encodeToString(data);
  }

  public static byte[] fromB64u(String s) throws Invalid {
    if (s == null || s.length() > 16_384) {
      throw new Invalid("no data");
    }
    try {
      return B64U_DEC.decode(s.replace('+', '-').replace('/', '_').replace("=", ""));
    } catch (IllegalArgumentException e) {
      throw new Invalid("bad base64url");
    }
  }

  public static ClientData clientData(byte[] json) throws Invalid {
    JsonNode n;
    try {
      n = JSON.readTree(json);
    } catch (RuntimeException e) {
      throw new Invalid("clientDataJSON is not JSON");
    }
    if (n == null || !n.isObject()) {
      throw new Invalid("clientDataJSON is not an object");
    }
    return new ClientData(
        n.path("type").asString(""),
        n.path("challenge").asString(""),
        n.path("origin").asString(""),
        n.path("crossOrigin").asBoolean(false));
  }

  public static AuthData authData(byte[] data) throws Invalid {
    if (data == null || data.length < 37) {
      throw new Invalid("authenticatorData too short");
    }
    ByteBuffer b = ByteBuffer.wrap(data);
    byte[] rpIdHash = new byte[32];
    b.get(rpIdHash);
    int flags = b.get() & 0xff;
    long count = Integer.toUnsignedLong(b.getInt());
    byte[] credentialId = null;
    if ((flags & FLAG_AT) != 0) {
      if (b.remaining() < 18) {
        throw new Invalid("attested credential data too short");
      }
      b.position(b.position() + 16); // AAGUID
      int len = b.getShort() & 0xffff;
      if (len == 0 || len > 1023 || b.remaining() < len) {
        throw new Invalid("bad credential id length");
      }
      credentialId = new byte[len];
      b.get(credentialId);
    }
    return new AuthData(rpIdHash, flags, count, credentialId);
  }

  public static byte[] sha256(byte[] data) {
    return Tokens.sha256(data);
  }

  /** Совпадает ли хеш домена из authenticatorData с нашим доменом. */
  public static boolean rpMatches(AuthData a, String rpId) {
    return MessageDigest.isEqual(
        a.rpIdHash(), sha256(rpId.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  /** Открытый ключ нужного алгоритму типа: P-256 для ES256, RSA от 2048 бит, Ed25519. */
  public static PublicKey publicKey(int alg, byte[] spki) throws Invalid {
    try {
      PublicKey key =
          switch (alg) {
            case ES256 -> KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki));
            case RS256 ->
                KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(spki));
            case EDDSA ->
                KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
            default -> throw new Invalid("unsupported algorithm " + alg);
          };
      if (key instanceof ECPublicKey ec && !isP256(ec.getParams())) {
        throw new Invalid("ES256 needs P-256");
      }
      if (key instanceof RSAPublicKey rsa && rsa.getModulus().bitLength() < 2048) {
        throw new Invalid("RSA key too short");
      }
      return key;
    } catch (GeneralSecurityException e) {
      throw new Invalid("bad public key");
    }
  }

  private static boolean isP256(ECParameterSpec p) {
    // Порядок группы P-256 (secp256r1).
    return p.getOrder()
        .equals(
            new java.math.BigInteger(
                "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16));
  }

  /** Подпись над authenticatorData ‖ SHA-256(clientDataJSON). */
  public static boolean verify(
      int alg, byte[] spki, byte[] authData, byte[] clientDataJson, byte[] signature)
      throws Invalid {
    PublicKey key = publicKey(alg, spki);
    byte[] signed = Arrays.copyOf(authData, authData.length + 32);
    System.arraycopy(sha256(clientDataJson), 0, signed, authData.length, 32);
    try {
      Signature s =
          Signature.getInstance(
              switch (alg) {
                case ES256 -> "SHA256withECDSA";
                case RS256 -> "SHA256withRSA";
                default -> "Ed25519";
              });
      s.initVerify(key);
      s.update(signed);
      return s.verify(signature);
    } catch (GeneralSecurityException e) {
      return false;
    }
  }
}
