package app.groupbase.auth;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.ApiClient;
import app.groupbase.IntegrationTest;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Вход по ключу против программного «аутентификатора»: он подписывает данные так же, как телефон
 * или ноутбук, — P-256 и формат authenticatorData из спецификации WebAuthn.
 */
class PasskeysIT extends IntegrationTest {

  /** Программный ключ: пара P-256, id и счётчик подписей. */
  static final class SoftKey {
    final KeyPair pair;
    final byte[] id = Tokens.randomBytes(16);
    int count;

    SoftKey() throws Exception {
      KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
      g.initialize(new ECGenParameterSpec("secp256r1"));
      pair = g.generateKeyPair();
    }

    byte[] authData(String rpId, int flags, boolean attested) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      out.writeBytes(Tokens.sha256(rpId.getBytes(StandardCharsets.UTF_8)));
      out.write(flags);
      out.writeBytes(ByteBuffer.allocate(4).putInt(count).array());
      if (attested) {
        out.writeBytes(new byte[16]);
        out.writeBytes(ByteBuffer.allocate(2).putShort((short) id.length).array());
        out.writeBytes(id);
        out.writeBytes(new byte[] {(byte) 0xa0}); // COSE-ключ сервер не разбирает
      }
      return out.toByteArray();
    }

    byte[] sign(byte[] authData, byte[] clientData) throws Exception {
      Signature s = Signature.getInstance("SHA256withECDSA");
      s.initSign(pair.getPrivate());
      s.update(authData);
      s.update(Tokens.sha256(clientData));
      return s.sign();
    }
  }

  private String site() {
    return "http://localhost:" + port;
  }

  /** Браузер на «localhost»: по IP WebAuthn не работает, поэтому хост — через прокси-заголовок. */
  private ApiClient browser(ApiClient c) {
    // Свой адрес у каждого «браузера»: неудачные попытки здесь не мешают входу в других тестах.
    String ip = "198.51.100." + (1 + (int) (Math.random() * 250));
    return c.header("X-Forwarded-Host", "localhost:" + port)
        .header("X-Forwarded-For", ip)
        .header("Origin", site());
  }

  private static byte[] clientData(String type, String challenge, String origin) {
    return ("{\"type\":\""
            + type
            + "\",\"challenge\":\""
            + challenge
            + "\",\"origin\":\""
            + origin
            + "\",\"crossOrigin\":false}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private ApiClient.Response register(ApiClient api, SoftKey key, String password) {
    var opts = api.post("/api/me/passkeys/options", Map.of("password", password));
    assertThat(opts.status()).as(opts.body()).isEqualTo(200);
    String challenge = opts.json().get("challenge").asString();
    Map<String, Object> body = new HashMap<>();
    body.put("credentialId", WebAuthn.b64u(key.id));
    body.put("clientDataJSON", WebAuthn.b64u(clientData("webauthn.create", challenge, site())));
    body.put("authenticatorData", WebAuthn.b64u(key.authData("localhost", 0x45, true)));
    body.put("publicKey", WebAuthn.b64u(key.pair.getPublic().getEncoded()));
    body.put("algorithm", WebAuthn.ES256);
    body.put("name", "iPhone");
    return api.post("/api/me/passkeys", body);
  }

  private ApiClient.Response login(ApiClient anon, SoftKey key, int flags) throws Exception {
    var opts = anon.post("/api/auth/passkey/options", Map.of());
    assertThat(opts.status()).as(opts.body()).isEqualTo(200);
    byte[] cd = clientData("webauthn.get", opts.json().get("challenge").asString(), site());
    byte[] ad = key.authData("localhost", flags, false);
    return anon.post(
        "/api/auth/passkey",
        Map.of(
            "credentialId", WebAuthn.b64u(key.id),
            "clientDataJSON", WebAuthn.b64u(cd),
            "authenticatorData", WebAuthn.b64u(ad),
            "signature", WebAuthn.b64u(key.sign(ad, cd))));
  }

  @Test
  void addPasskeyThenLogInWithoutPassword() throws Exception {
    long g = newGroup("Ключи");
    TestUser u = newUser(g, "student");
    ApiClient api = browser(u.api());
    SoftKey key = new SoftKey();

    // Добавить ключ можно, только подтвердив пароль.
    var wrong = api.post("/api/me/passkeys/options", Map.of("password", "не-тот-пароль"));
    assertThat(wrong.status()).isEqualTo(403);
    var opts = api.post("/api/me/passkeys/options", Map.of("password", PASSWORD));
    assertThat(opts.json().get("rp").get("id").asString()).isEqualTo("localhost");
    assertThat(opts.json().get("authenticatorSelection").get("userVerification").asString())
        .isEqualTo("required");
    assertThat(opts.json().get("authenticatorSelection").has("authenticatorAttachment"))
        .as("обычный ключ — где удобнее: в телефоне, ноутбуке или на USB")
        .isFalse();
    var usb =
        api.post("/api/me/passkeys/options", Map.of("password", PASSWORD, "securityKey", true));
    assertThat(usb.json().get("authenticatorSelection").get("authenticatorAttachment").asString())
        .isEqualTo("cross-platform");
    assertThat(usb.json().get("hints").get(0).asString()).isEqualTo("security-key");

    var added = register(api, key, PASSWORD);
    assertThat(added.status()).as(added.body()).isEqualTo(200);
    assertThat(added.json().get("name").asString()).isEqualTo("iPhone");
    JsonNode list = api.get("/api/me/passkeys").json();
    assertThat(list.size()).isEqualTo(1);
    assertThat(list.get(0).get("site").asString())
        .as("для какого адреса ключ — на другом им не войти")
        .isEqualTo("localhost");

    // Тот же ключ второй раз не добавить — браузер получит его в excludeCredentials.
    var again = api.post("/api/me/passkeys/options", Map.of("password", PASSWORD));
    assertThat(again.json().get("excludeCredentials").get(0).get("id").asString())
        .isEqualTo(WebAuthn.b64u(key.id));

    // Вход: ни логина, ни пароля — только подпись ключа.
    ApiClient anon = browser(client());
    key.count = 1;
    var in = login(anon, key, 0x05);
    assertThat(in.status()).as(in.body()).isEqualTo(200);
    assertThat(anon.get("/api/me").json().get("user").get("username").asString())
        .isEqualTo(u.username());
    assertThat(api.get("/api/me/passkeys").json().get(0).get("lastUsedAt").isNull()).isFalse();

    // Счётчик не вырос — ключ мог быть скопирован: не пускаем.
    var cloned = login(browser(client()), key, 0x05);
    assertThat(cloned.status()).isEqualTo(401);
    // Без подтверждения человеком (UV) — тоже нет.
    key.count = 5;
    assertThat(login(browser(client()), key, 0x01).status()).isEqualTo(401);
    // Чужой ключ с тем же id — подпись не сойдётся.
    SoftKey forged = new SoftKey();
    System.arraycopy(key.id, 0, forged.id, 0, key.id.length);
    forged.count = 10;
    assertThat(login(browser(client()), forged, 0x05).status()).isEqualTo(401);

    // Удалённым ключом не войти.
    long id = api.get("/api/me/passkeys").json().get(0).get("id").asLong();
    assertThat(api.delete("/api/me/passkeys/" + id).status()).isEqualTo(200);
    key.count = 20;
    assertThat(login(browser(client()), key, 0x05).status()).isEqualTo(401);
  }

  @Test
  void challengeIsSingleUseAndBoundToSite() throws Exception {
    long g = newGroup("Ключи 2");
    TestUser u = newUser(g, "student");
    ApiClient api = browser(u.api());
    SoftKey key = new SoftKey();
    assertThat(register(api, key, PASSWORD).status()).isEqualTo(200);

    ApiClient anon = browser(client());
    var opts = anon.post("/api/auth/passkey/options", Map.of());
    String challenge = opts.json().get("challenge").asString();
    key.count = 1;
    byte[] cd = clientData("webauthn.get", challenge, site());
    byte[] ad = key.authData("localhost", 0x05, false);
    Map<String, Object> body =
        Map.of(
            "credentialId", WebAuthn.b64u(key.id),
            "clientDataJSON", WebAuthn.b64u(cd),
            "authenticatorData", WebAuthn.b64u(ad),
            "signature", WebAuthn.b64u(key.sign(ad, cd)));
    assertThat(anon.post("/api/auth/passkey", body).status()).isEqualTo(200);
    // Повтор того же ответа — вызов уже использован.
    assertThat(browser(client()).post("/api/auth/passkey", body).status()).isEqualTo(401);

    // Подпись для другого сайта (фишинг) не подходит, даже если вызов настоящий.
    ApiClient other = browser(client());
    String c2 =
        other.post("/api/auth/passkey/options", Map.of()).json().get("challenge").asString();
    key.count = 2;
    byte[] evil = clientData("webauthn.get", c2, "https://evil.example");
    byte[] ad2 = key.authData("localhost", 0x05, false);
    var phished =
        other.post(
            "/api/auth/passkey",
            Map.of(
                "credentialId", WebAuthn.b64u(key.id),
                "clientDataJSON", WebAuthn.b64u(evil),
                "authenticatorData", WebAuthn.b64u(ad2),
                "signature", WebAuthn.b64u(key.sign(ad2, evil))));
    assertThat(phished.status()).isEqualTo(401);

    // Запрос с чужого адреса и по IP не принимается вовсе.
    assertThat(
            client()
                .header("Origin", "https://evil.example")
                .post("/api/auth/passkey/options", Map.of())
                .status())
        .isEqualTo(400);
    assertThat(
            client()
                .header("Origin", "http://127.0.0.1:" + port)
                .post("/api/auth/passkey/options", Map.of())
                .status())
        .isEqualTo(400);
  }

  @Test
  void oneAddressCannotHoardChallenges() throws Exception {
    long g = newGroup("Ключи 3");
    TestUser u = newUser(g, "student");
    SoftKey key = new SoftKey();
    assertThat(register(browser(u.api()), key, PASSWORD).status()).isEqualTo(200);

    // С одного адреса живут только последние Passkeys.PER_IP вызовов: самый первый уже вытеснен.
    ApiClient flood = browser(client());
    List<String> challenges = new ArrayList<>();
    for (int i = 0; i < Passkeys.PER_IP + 2; i++) {
      challenges.add(
          flood.post("/api/auth/passkey/options", Map.of()).json().get("challenge").asString());
    }
    key.count = 1;
    assertThat(signIn(flood, key, challenges.getFirst()).status()).isEqualTo(401);
    key.count = 2;
    assertThat(signIn(flood, key, challenges.getLast()).status()).isEqualTo(200);
  }

  private ApiClient.Response signIn(ApiClient c, SoftKey key, String challenge) throws Exception {
    byte[] cd = clientData("webauthn.get", challenge, site());
    byte[] ad = key.authData("localhost", 0x05, false);
    return c.post(
        "/api/auth/passkey",
        Map.of(
            "credentialId", WebAuthn.b64u(key.id),
            "clientDataJSON", WebAuthn.b64u(cd),
            "authenticatorData", WebAuthn.b64u(ad),
            "signature", WebAuthn.b64u(key.sign(ad, cd))));
  }

  @Test
  void pageMayUseCameraForQrAndPasskeys() {
    var r = client().get("/api/health");
    String policy = r.raw().headers().firstValue("Permissions-Policy").orElse("");
    assertThat(policy).contains("camera=(self)", "publickey-credentials-get=(self)");
  }
}
