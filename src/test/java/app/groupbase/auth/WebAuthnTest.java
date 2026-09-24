package app.groupbase.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class WebAuthnTest {

  private static final byte[] CLIENT =
      "{\"type\":\"webauthn.get\"}".getBytes(StandardCharsets.UTF_8);

  private static byte[] authData(int flags, int count) {
    byte[] a = new byte[37];
    System.arraycopy(Tokens.sha256("site.ru".getBytes(StandardCharsets.UTF_8)), 0, a, 0, 32);
    a[32] = (byte) flags;
    a[36] = (byte) count;
    return a;
  }

  private static byte[] sign(String alg, KeyPair k, byte[] authData) throws Exception {
    Signature s = Signature.getInstance(alg);
    s.initSign(k.getPrivate());
    s.update(authData);
    s.update(Tokens.sha256(CLIENT));
    return s.sign();
  }

  @Test
  void parsesFlagsCounterAndRpHash() throws Exception {
    WebAuthn.AuthData a = WebAuthn.authData(authData(0x05, 7));
    assertThat(a.userPresent()).isTrue();
    assertThat(a.userVerified()).isTrue();
    assertThat(a.signCount()).isEqualTo(7);
    assertThat(a.credentialId()).isNull();
    assertThat(WebAuthn.rpMatches(a, "site.ru")).isTrue();
    assertThat(WebAuthn.rpMatches(a, "evil.ru")).isFalse();
    assertThatThrownBy(() -> WebAuthn.authData(new byte[10])).isInstanceOf(WebAuthn.Invalid.class);
    // Флаг AT без данных ключа — обрезанный ответ.
    assertThatThrownBy(() -> WebAuthn.authData(authData(0x45, 0)))
        .isInstanceOf(WebAuthn.Invalid.class);
  }

  @Test
  void verifiesEs256Ed25519AndRs256() throws Exception {
    byte[] ad = authData(0x05, 1);
    KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
    ec.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair p256 = ec.generateKeyPair();
    assertThat(
            WebAuthn.verify(
                WebAuthn.ES256,
                p256.getPublic().getEncoded(),
                ad,
                CLIENT,
                sign("SHA256withECDSA", p256, ad)))
        .isTrue();

    KeyPair ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    assertThat(
            WebAuthn.verify(
                WebAuthn.EDDSA, ed.getPublic().getEncoded(), ad, CLIENT, sign("Ed25519", ed, ad)))
        .isTrue();

    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(2048);
    KeyPair r = rsa.generateKeyPair();
    byte[] sig = sign("SHA256withRSA", r, ad);
    assertThat(WebAuthn.verify(WebAuthn.RS256, r.getPublic().getEncoded(), ad, CLIENT, sig))
        .isTrue();
    // Другие данные — подпись не сходится.
    byte[] changed = Arrays.copyOf(ad, ad.length);
    changed[36] = 9;
    assertThat(WebAuthn.verify(WebAuthn.RS256, r.getPublic().getEncoded(), changed, CLIENT, sig))
        .isFalse();
  }

  @Test
  void rejectsWeakOrMismatchedKeys() throws Exception {
    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(1024);
    byte[] weak = rsa.generateKeyPair().getPublic().getEncoded();
    assertThatThrownBy(() -> WebAuthn.publicKey(WebAuthn.RS256, weak))
        .isInstanceOf(WebAuthn.Invalid.class);
    KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
    ec.initialize(new ECGenParameterSpec("secp384r1"));
    byte[] p384 = ec.generateKeyPair().getPublic().getEncoded();
    assertThatThrownBy(() -> WebAuthn.publicKey(WebAuthn.ES256, p384))
        .isInstanceOf(WebAuthn.Invalid.class);
    // RSA-ключ, выданный за ES256.
    rsa.initialize(2048);
    byte[] rsaKey = rsa.generateKeyPair().getPublic().getEncoded();
    assertThatThrownBy(() -> WebAuthn.publicKey(WebAuthn.ES256, rsaKey))
        .isInstanceOf(WebAuthn.Invalid.class);
    assertThatThrownBy(() -> WebAuthn.publicKey(-999, rsaKey)).isInstanceOf(WebAuthn.Invalid.class);
  }

  @Test
  void clientDataAndBase64url() throws Exception {
    WebAuthn.ClientData c =
        WebAuthn.clientData(
            "{\"type\":\"webauthn.create\",\"challenge\":\"abc\",\"origin\":\"https://site.ru\"}"
                .getBytes(StandardCharsets.UTF_8));
    assertThat(c.type()).isEqualTo("webauthn.create");
    assertThat(c.origin()).isEqualTo("https://site.ru");
    assertThat(c.crossOrigin()).isFalse();
    assertThatThrownBy(() -> WebAuthn.clientData("[]".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(WebAuthn.Invalid.class);
    byte[] data = {(byte) 0xfb, (byte) 0xff, 0x01};
    assertThat(WebAuthn.fromB64u(WebAuthn.b64u(data))).isEqualTo(data);
    // Обычный base64 с «+», «/» и «=» тоже понимаем.
    assertThat(WebAuthn.fromB64u("+/8B")).isEqualTo(data);
    assertThatThrownBy(() -> WebAuthn.fromB64u("***")).isInstanceOf(WebAuthn.Invalid.class);
  }
}
