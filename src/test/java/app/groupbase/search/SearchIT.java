package app.groupbase.search;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.ApiClient;
import app.groupbase.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class SearchIT extends IntegrationTest {

  private static long subject(ApiClient api, long group, String name) {
    return api.post(
            "/api/groups/" + group + "/subjects", Map.of("name", name, "teacher", "Петрова Е. А."))
        .json()
        .get("id")
        .asLong();
  }

  private long homework(ApiClient api, long subject, long group, String title, String body) {
    var r =
        api.post(
            "/api/homework",
            Map.of(
                "subjectId", subject,
                "title", title,
                "body", body,
                "dueAt", clock.millis() + 86_400_000L,
                "groupIds", List.of(group)));
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    return r.json().get("id").asLong();
  }

  private static List<String> found(ApiClient api, String q) {
    var r = api.get("/api/search?q=" + java.net.URLEncoder.encode(q, StandardCharsets.UTF_8));
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    List<String> out = new ArrayList<>();
    for (JsonNode n : r.json().get("items")) {
      out.add(n.get("kind").asString() + ":" + n.get("id").asLong());
    }
    return out;
  }

  @Test
  void findsByWordFormsOnlyWhatUserMaySee() {
    long a = newGroup("Поиск-А");
    long b = newGroup("Поиск-Б");
    TestUser headman = newUser(a, "headman");
    TestUser student = newUser(a, "student");
    TestUser stranger = newUser(b, "student");
    long s = subject(headman.api(), a, "Математический анализ");
    long hw = homework(headman.api(), s, a, "Типовой расчёт по рядам", "Решить задачи 1–12");
    long hidden = homework(headman.api(), s, a, "Черновик задачи по рядам", "");
    headman.api().put("/api/homework/" + hidden + "/hidden", Map.of("value", true));
    var news =
        headman
            .api()
            .post(
                "/api/news",
                Map.of(
                    "title",
                    "Перенос пары",
                    "body",
                    "Лекция про **ряды Фурье** в 314",
                    "groupIds",
                    List.of(a)));
    long newsId = news.json().get("id").asLong();

    // Формы слова, «ё» и «е», регистр.
    assertThat(found(student.api(), "задача")).contains("homework:" + hw);
    assertThat(found(student.api(), "РАСЧЕТ")).containsExactly("homework:" + hw);
    var title = student.api().get("/api/search?q=%D1%80%D0%B0%D1%81%D1%87%D0%B5%D1%82").json();
    StringBuilder shown = new StringBuilder();
    title.get("items").get(0).get("title").forEach(x -> shown.append(x.get("text").asString()));
    assertThat(shown.toString())
        .as("буква «ё» в выдаче сохраняется")
        .isEqualTo("Типовой расчёт по рядам");
    assertThat(found(student.api(), "ряды")).contains("homework:" + hw, "news:" + newsId);
    assertThat(found(student.api(), "математическому")).containsExactly("subject:" + s);
    assertThat(found(student.api(), "петрова")).containsExactly("subject:" + s);

    // Скрытое видят автор и модераторы, студент — нет; чужая группа не видит ничего.
    assertThat(found(student.api(), "черновик")).isEmpty();
    assertThat(found(headman.api(), "черновик")).containsExactly("homework:" + hidden);
    assertThat(found(stranger.api(), "ряды")).isEmpty();
    assertThat(found(stranger.api(), "математический")).isEmpty();

    // Фильтр по разделу и подсветка совпадений.
    var r = student.api().get("/api/search?q=%D1%80%D1%8F%D0%B4%D1%8B&kind=news").json();
    assertThat(r.get("items").size()).isEqualTo(1);
    JsonNode snippet = r.get("items").get(0).get("snippet");
    boolean anyHit = false;
    for (JsonNode seg : snippet) {
      anyHit |= seg.get("hit").asBoolean();
      assertThat(seg.get("text").asString()).doesNotContain("**");
    }
    assertThat(anyHit).isTrue();
    assertThat(student.api().get("/api/search?q=x&kind=users").status()).isEqualTo(400);
  }

  @Test
  void indexFollowsEditsDeletesAndMaterials() {
    long g = newGroup("Индекс");
    TestUser headman = newUser(g, "headman");
    long s = subject(headman.api(), g, "Физика");
    long hw = homework(headman.api(), s, g, "Лабораторная про маятник", "");
    assertThat(found(headman.api(), "маятника")).containsExactly("homework:" + hw);

    headman
        .api()
        .patch(
            "/api/homework/" + hw,
            Map.of(
                "subjectId",
                s,
                "title",
                "Лабораторная про пружину",
                "body",
                "",
                "dueAt",
                clock.millis() + 86_400_000L,
                "groupIds",
                List.of(g)));
    assertThat(found(headman.api(), "маятник")).isEmpty();
    assertThat(found(headman.api(), "пружины")).containsExactly("homework:" + hw);

    assertThat(headman.api().delete("/api/homework/" + hw).status()).isEqualTo(200);
    assertThat(found(headman.api(), "пружина")).isEmpty();

    long file =
        headman
            .api()
            .upload("Конспект Гюйгенс.pdf", "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.UTF_8))
            .json()
            .get("id")
            .asLong();
    long m =
        headman
            .api()
            .post(
                "/api/subjects/" + s + "/materials",
                Map.of("kind", "file", "fileId", file, "title", "Лекция 3"))
            .json()
            .get("id")
            .asLong();
    assertThat(found(headman.api(), "гюйгенс")).containsExactly("material:" + m);
  }

  @Test
  void strangeInputDoesNotBreakSearch() {
    long g = newGroup("Ввод");
    TestUser student = newUser(g, "student");
    for (String q : List.of("\"", "*", "NEAR(", "a OR", "(((", "-", "^", "ё")) {
      assertThat(
              student
                  .api()
                  .get("/api/search?q=" + java.net.URLEncoder.encode(q, StandardCharsets.UTF_8))
                  .status())
          .as(q)
          .isEqualTo(200);
    }
    assertThat(student.api().get("/api/search?q=" + "a".repeat(201)).status()).isEqualTo(400);
  }

  @org.springframework.beans.factory.annotation.Autowired
  private app.groupbase.files.FileText fileText;

  private static byte[] pdf(String text) throws Exception {
    try (var doc = new org.apache.pdfbox.pdmodel.PDDocument();
        var out = new java.io.ByteArrayOutputStream()) {
      var page = new org.apache.pdfbox.pdmodel.PDPage();
      doc.addPage(page);
      try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
        cs.beginText();
        cs.setFont(
            new org.apache.pdfbox.pdmodel.font.PDType1Font(
                org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),
            12);
        cs.newLineAtOffset(72, 700);
        cs.showText(text);
        cs.endText();
      }
      doc.save(out);
      return out.toByteArray();
    }
  }

  private static byte[] docx(String text) throws Exception {
    var out = new java.io.ByteArrayOutputStream();
    try (var zip = new java.util.zip.ZipOutputStream(out)) {
      zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
      zip.write(
          ("<w:document><w:body><w:p><w:r><w:t>"
                  + text
                  + "</w:t></w:r></w:p></w:body></w:document>")
              .getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return out.toByteArray();
  }

  private long material(TestUser u, long subject, String name, byte[] data) {
    long file = u.api().upload(name, data).json().get("id").asLong();
    var r =
        u.api()
            .post(
                "/api/subjects/" + subject + "/materials",
                Map.of("kind", "file", "fileId", file, "title", name));
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    return r.json().get("id").asLong();
  }

  @Test
  void findsTextInsideFilesWithFiltersAndOperators() throws Exception {
    long a = newGroup("Файлы-поиск");
    TestUser headman = newUser(a, "headman");
    TestUser student = newUser(a, "student");
    TestUser stranger = newUser(newGroup("Чужие файлы"), "student");
    long phys = subject(headman.api(), a, "Физика");
    long math = subject(headman.api(), a, "Матанализ");
    long pdfMat = material(headman, phys, "Лекция 3.pdf", pdf("Maxwell equations and waveguides"));
    long docMat = material(headman, math, "Ряды.docx", docx("Формула Эйлера и ряды Тейлора"));
    long mdMat =
        material(
            headman,
            math,
            "Конспект.md",
            "# Пределы\nЗамечательный предел и отчёт".getBytes(StandardCharsets.UTF_8));
    fileText.indexNow();

    assertThat(found(student.api(), "waveguides")).containsExactly("file:" + pdfMat);
    assertThat(found(student.api(), "эйлера")).contains("file:" + docMat);
    assertThat(found(student.api(), "замечательный")).contains("file:" + mdMat);
    // Чужая группа не видит ни материалов, ни текста их файлов.
    assertThat(found(stranger.api(), "waveguides")).isEmpty();

    var r = student.api().get("/api/search?q=waveguides").json().get("items").get(0);
    assertThat(r.get("url").asString()).isEqualTo("/materials/" + pdfMat);
    assertThat(r.get("file").asString()).isEqualTo("Лекция 3.pdf");
    assertThat(r.get("snippet").toString()).contains("waveguides");

    // Фраза, исключение и фильтр по предмету.
    assertThat(found(student.api(), "\"ряды тейлора\"")).contains("file:" + docMat);
    assertThat(found(student.api(), "\"тейлора ряды\"")).doesNotContain("file:" + docMat);
    assertThat(found(student.api(), "предел -отчёт")).doesNotContain("file:" + mdMat);
    var onlyPhys =
        student
            .api()
            .get(
                "/api/search?q="
                    + java.net.URLEncoder.encode("эйлера", StandardCharsets.UTF_8)
                    + "&subject="
                    + phys)
            .json()
            .get("items");
    assertThat(onlyPhys.size()).isZero();
  }
}
