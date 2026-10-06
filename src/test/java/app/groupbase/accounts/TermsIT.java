package app.groupbase.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.ApiClient;
import app.groupbase.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TermsIT extends IntegrationTest {

  @Test
  void consentIsAskedOnceAndAgainAfterRulesChange() {
    ApiClient admin = admin();
    long g = newGroup("Правила");
    ApiClient student = newUser(g, "student").api();

    // Правила читают и без входа.
    var rules = client().get("/api/terms");
    assertThat(rules.status()).isEqualTo(200);
    String version = rules.json().get("version").asString();
    assertThat(version).startsWith(Terms.BASE + ".");

    assertThat(student.get("/api/me").json().get("user").get("termsAccepted").asBoolean())
        .isFalse();
    assertThat(student.patch("/api/me/preferences", Map.of("terms", version)).status())
        .isEqualTo(200);
    assertThat(student.get("/api/me").json().get("user").get("termsAccepted").asBoolean()).isTrue();

    // Администратор дописал правила группы — спросят снова; старую версию принять нельзя.
    assertThat(student.put("/api/admin/terms", Map.of("extra", "x")).status()).isEqualTo(403);
    var changed = admin.put("/api/admin/terms", Map.of("extra", "Не **спамить** в чате"));
    assertThat(changed.status()).as(changed.body()).isEqualTo(200);
    assertThat(changed.json().get("extraHtml").asString()).contains("<strong>спамить</strong>");
    String next = changed.json().get("version").asString();
    assertThat(next).isNotEqualTo(version);
    assertThat(student.get("/api/me").json().get("user").get("termsAccepted").asBoolean())
        .isFalse();
    assertThat(student.patch("/api/me/preferences", Map.of("terms", version)).status())
        .isEqualTo(409);
    assertThat(student.patch("/api/me/preferences", Map.of("terms", next)).status()).isEqualTo(200);
    // Тот же текст ещё раз — версия не меняется.
    assertThat(
            admin
                .put("/api/admin/terms", Map.of("extra", "Не **спамить** в чате"))
                .json()
                .get("version")
                .asString())
        .isEqualTo(next);
  }
}
