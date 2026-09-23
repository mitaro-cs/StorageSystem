package app.groupbase.notify;

import static app.groupbase.notify.PushCrypto.b64;
import static app.groupbase.notify.PushCrypto.unb64;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Проверка по примеру из RFC 8291, раздел 5 и приложение A. */
class PushCryptoTest {

  static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
  static final String AS_PUBLIC =
      "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
  static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
  static final String UA_PUBLIC =
      "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
  static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";
  static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
  static final String CIPHERTEXT =
      "8pfeW0KbunFT06SuDKoJH9Ql87S1QUrdirN6GcG7sFz1y1sqLgVi1VhjVkHsUoEsbI_0LpXMuGvnzQ";

  @Test
  void matchesRfc8291Example() {
    byte[] plaintext = "When I grow up, I want to be a watermelon".getBytes(StandardCharsets.UTF_8);
    assertThat(b64(PushCrypto.publicFromPrivate(unb64(AS_PRIVATE)))).isEqualTo(AS_PUBLIC);
    KeyPair as =
        new KeyPair(
            PushCrypto.publicKey(unb64(AS_PUBLIC)), PushCrypto.privateKey(unb64(AS_PRIVATE)));
    byte[] body = PushCrypto.encrypt(plaintext, unb64(UA_PUBLIC), unb64(AUTH), as, unb64(SALT));

    ByteBuffer expectedHeader = ByteBuffer.allocate(86);
    expectedHeader.put(unb64(SALT)).putInt(4096).put((byte) 65).put(unb64(AS_PUBLIC));
    assertThat(Arrays.copyOf(body, 86)).isEqualTo(expectedHeader.array());
    assertThat(b64(Arrays.copyOfRange(body, 86, body.length))).isEqualTo(CIPHERTEXT);
  }

  @Test
  void deviceCanDecryptRandomisedMessage() throws Exception {
    KeyPair ua = PushCrypto.newKeyPair();
    byte[] auth = new byte[16];
    byte[] uaPublic = PushCrypto.encode((java.security.interfaces.ECPublicKey) ua.getPublic());
    byte[] msg = "{\"title\":\"Новое задание\"}".getBytes(StandardCharsets.UTF_8);
    byte[] body = PushCrypto.encrypt(msg, uaPublic, auth);
    assertThat(decrypt(body, ua, uaPublic, auth)).isEqualTo(msg);
  }

  @Test
  void rejectsBadKeysAndLongMessages() {
    byte[] notOnCurve = unb64(UA_PUBLIC);
    notOnCurve[64] ^= 1;
    assertThatThrownBy(() -> PushCrypto.publicKey(notOnCurve))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PushCrypto.publicKey(new byte[33]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PushCrypto.encrypt(
                    new byte[PushCrypto.MAX_PLAINTEXT + 1], unb64(UA_PUBLIC), unb64(AUTH)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void vapidJwtIsVerifiableEs256() throws Exception {
    KeyPair kp = PushCrypto.newKeyPair();
    String jwt =
        PushCrypto.vapidJwt(
            "https://fcm.googleapis.com",
            "mailto:admin@example.org",
            1_900_000_000L,
            kp.getPrivate());
    String[] parts = jwt.split("\\.");
    assertThat(parts).hasSize(3);
    assertThat(new String(unb64(parts[1]), StandardCharsets.UTF_8))
        .isEqualTo(
            "{\"aud\":\"https://fcm.googleapis.com\",\"exp\":1900000000,\"sub\":\"mailto:admin@example.org\"}");
    Signature v = Signature.getInstance("SHA256withECDSAinP1363Format");
    v.initVerify(kp.getPublic());
    v.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
    assertThat(unb64(parts[2])).hasSize(64);
    assertThat(v.verify(unb64(parts[2]))).isTrue();
  }

  /** Расшифровка на стороне устройства (RFC 8291) — чтобы проверить круг целиком. */
  static byte[] decrypt(byte[] body, KeyPair ua, byte[] uaPublic, byte[] auth) throws Exception {
    ByteBuffer b = ByteBuffer.wrap(body);
    byte[] salt = new byte[16];
    b.get(salt);
    b.getInt();
    byte[] asPublic = new byte[b.get()];
    b.get(asPublic);
    byte[] ct = new byte[b.remaining()];
    b.get(ct);
    KeyAgreement ka = KeyAgreement.getInstance("ECDH");
    ka.init(ua.getPrivate());
    ka.doPhase(PushCrypto.publicKey(asPublic), true);
    byte[] ecdh = ka.generateSecret();
    byte[] prkKey = PushCrypto.hmac(auth, ecdh);
    byte[] ikm =
        PushCrypto.hmac(
            prkKey,
            PushCrypto.concat(
                "WebPush: info".getBytes(StandardCharsets.US_ASCII),
                new byte[] {0},
                uaPublic,
                asPublic,
                new byte[] {1}));
    byte[] prk = PushCrypto.hmac(salt, ikm);
    byte[] cek =
        Arrays.copyOf(
            PushCrypto.hmac(
                prk,
                PushCrypto.concat(
                    "Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII),
                    new byte[] {0, 1})),
            16);
    byte[] nonce =
        Arrays.copyOf(
            PushCrypto.hmac(
                prk,
                PushCrypto.concat(
                    "Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII),
                    new byte[] {0, 1})),
            12);
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
    byte[] padded = c.doFinal(ct);
    int end = padded.length - 1;
    while (padded[end] == 0) {
      end--;
    }
    assertThat(padded[end]).isEqualTo((byte) 2);
    return Arrays.copyOf(padded, end);
  }
}
