package app.groupbase.content;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.IntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MaterialsIT extends IntegrationTest {

  private static final byte[] PDF =
      ("%PDF-1.4\n% Конспект лекции: ряды Фурье\n" + "x".repeat(5000) + "\n%%EOF\n")
          .getBytes(StandardCharsets.UTF_8);

  private long subject(long group) {
    return admin()
        .post("/api/groups/" + group + "/subjects", Map.of("name", "Физика " + uniq()))
        .json()
        .get("id")
        .asLong();
  }

  @Test
  void headmanUploadsStudentDownloadsAndDiskIsEncrypted() throws IOException {
    long g = newGroup("Файлы");
    TestUser headman = newUser(g, "headman");
    TestUser student = newUser(g, "student");
    long s = subject(g);

    var up = headman.api().upload("Лекция 1.pdf", PDF);
    assertThat(up.status()).as(up.body()).isEqualTo(200);
    assertThat(up.json().get("mime").asString()).isEqualTo("application/pdf");
    long fileId = up.json().get("id").asLong();

    // Пока файл не прикреплён, студент его не видит.
    assertThat(student.api().download("/api/files/" + fileId).statusCode()).isEqualTo(404);

    var m =
        headman
            .api()
            .post("/api/subjects/" + s + "/materials", Map.of("kind", "file", "fileId", fileId));
    assertThat(m.status()).as(m.body()).isEqualTo(200);
    assertThat(m.json().get("status").asString()).isEqualTo("published");
    assertThat(m.json().get("title").asString()).isEqualTo("Лекция 1.pdf");

    var dl = student.api().download("/api/files/" + fileId);
    assertThat(dl.statusCode()).isEqualTo(200);
    assertThat(dl.body()).isEqualTo(PDF);
    assertThat(dl.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("inline");
    assertThat(dl.headers().firstValue("Content-Type").orElseThrow()).isEqualTo("application/pdf");
    assertThat(dl.headers().firstValue("Content-Security-Policy")).isEmpty();
    assertThat(dl.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");

    // На диске — только шифротекст с UUID вместо имени.
    try (Stream<Path> files = Files.walk(props.filesDir())) {
      for (Path p : files.filter(Files::isRegularFile).toList()) {
        String raw = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
        assertThat(raw).doesNotContain("%PDF").doesNotContain("%%EOF");
        assertThat(p.getFileName().toString()).matches("[0-9a-f-]{36}");
      }
    }

    // Файл нельзя прикрепить второй раз.
    assertThat(
            headman
                .api()
                .post("/api/subjects/" + s + "/materials", Map.of("kind", "file", "fileId", fileId))
                .status())
        .isEqualTo(403);
  }

  @Test
  void manyFilesAtOnceSendOneNotification() throws InterruptedException {
    long g = newGroup("Пачка");
    TestUser headman = newUser(g, "headman");
    TestUser student = newUser(g, "student");
    long s = subject(g);
    List<Long> ids = new java.util.ArrayList<>();
    for (int i = 1; i <= 3; i++) {
      ids.add(headman.api().upload("Лаба " + i + ".pdf", PDF).json().get("id").asLong());
    }
    String url = "/api/subjects/" + s + "/materials/batch";
    assertThat(headman.api().post(url, Map.of("fileIds", List.of())).status()).isEqualTo(400);
    var r =
        headman
            .api()
            .post(url, Map.of("fileIds", ids, "description", "", "notice", "Добавлены лабы 1–3"));
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    assertThat(r.json().size()).isEqualTo(3);
    // Тот же файл второй раз не прикрепить – и пачка целиком не проходит.
    assertThat(headman.api().post(url, Map.of("fileIds", ids)).status()).isEqualTo(403);

    long until = System.currentTimeMillis() + 5000;
    tools.jackson.databind.JsonNode items;
    do {
      Thread.sleep(100);
      items = student.api().get("/api/notifications").json().get("items");
    } while (items.isEmpty() && System.currentTimeMillis() < until);
    Thread.sleep(300);
    items = student.api().get("/api/notifications").json().get("items");
    assertThat(items.size()).isEqualTo(1);
    assertThat(items.get(0).get("body").asString()).isEqualTo("Добавлены лабы 1–3");
    assertThat(items.get(0).get("title").asString()).startsWith("Новые материалы");
  }

  @Test
  void listIsInNaturalOrderAndEachPersonCanDismiss() {
    long g = newGroup("Порядок");
    TestUser headman = newUser(g, "headman");
    TestUser student = newUser(g, "student");
    long s = subject(g);
    for (String n : List.of("Лабораторная 10", "Лабораторная 2", "лабораторная 1", "Билеты")) {
      headman
          .api()
          .post(
              "/api/subjects/" + s + "/materials",
              Map.of("kind", "note", "title", n, "description", n));
    }
    var list = student.api().get("/api/subjects/" + s + "/materials").json().get("materials");
    List<String> titles = new java.util.ArrayList<>();
    list.forEach(m -> titles.add(m.get("title").asString()));
    assertThat(titles)
        .containsExactly("Билеты", "лабораторная 1", "Лабораторная 2", "Лабораторная 10");

    // «Скрыть у себя» – только у студента, у старосты на месте.
    long id = list.get(0).get("id").asLong();
    var hid = student.api().put("/api/materials/" + id + "/dismissed", Map.of("value", true));
    assertThat(hid.status()).as(hid.body()).isEqualTo(200);
    assertThat(hid.json().get("dismissed").asBoolean()).isTrue();
    var again = student.api().get("/api/subjects/" + s + "/materials").json().get("materials");
    assertThat(again.get(0).get("dismissed").asBoolean()).isTrue();
    var theirs = headman.api().get("/api/subjects/" + s + "/materials").json().get("materials");
    assertThat(theirs.get(0).get("dismissed").asBoolean()).isFalse();
    var back = student.api().put("/api/materials/" + id + "/dismissed", Map.of("value", false));
    assertThat(back.json().get("dismissed").asBoolean()).isFalse();
  }

  @Test
  void otherGroupCannotReadFiles() {
    long a = newGroup("Ф-A");
    long b = newGroup("Ф-B");
    TestUser headA = newUser(a, "headman");
    TestUser studentB = newUser(b, "student");
    long s = subject(a);
    long fileId = headA.api().upload("a.pdf", PDF).json().get("id").asLong();
    headA.api().post("/api/subjects/" + s + "/materials", Map.of("kind", "file", "fileId", fileId));
    assertThat(studentB.api().download("/api/files/" + fileId).statusCode()).isEqualTo(404);
    assertThat(studentB.api().get("/api/subjects/" + s + "/materials").status()).isEqualTo(404);
  }

  @Test
  void studentSuggestionsNeedApproval() {
    long g = newGroup("Премодерация");
    TestUser headman = newUser(g, "headman");
    TestUser s1 = newUser(g, "student");
    TestUser s2 = newUser(g, "student");
    long s = subject(g);
    Map<String, Object> link =
        Map.of("kind", "link", "url", "https://example.org/lecture", "title", "Запись лекции");

    // Староста выключил студентам «выкладывать» и «задания» (по умолчанию включены), а ⚙
    // «предлагать» выключено: студент не может ни загрузить файл, ни предложить ссылку.
    for (String perm : List.of("upload_materials", "publish_homework")) {
      headman
          .api()
          .put(
              "/api/groups/" + g + "/permissions",
              Map.of("role", "student", "permission", perm, "allowed", false));
    }
    assertThat(s1.api().upload("x.pdf", PDF).status()).isEqualTo(403);
    assertThat(s1.api().post("/api/subjects/" + s + "/materials", link).status()).isEqualTo(403);

    headman
        .api()
        .put(
            "/api/groups/" + g + "/permissions",
            Map.of("role", "student", "permission", "suggest_materials", "allowed", true));
    var suggested = s1.api().post("/api/subjects/" + s + "/materials", link);
    assertThat(suggested.status()).as(suggested.body()).isEqualTo(200);
    assertThat(suggested.json().get("status").asString()).isEqualTo("pending");
    long id = suggested.json().get("id").asLong();

    assertThat(s2.api().get("/api/materials/" + id).status()).isEqualTo(404);
    assertThat(s2.api().get("/api/subjects/" + s + "/materials").json().get("materials").size())
        .isZero();
    assertThat(headman.api().get("/api/materials/pending").json().size()).isGreaterThanOrEqualTo(1);
    assertThat(s1.api().post("/api/materials/" + id + "/approve", null).status()).isEqualTo(403);
    assertThat(headman.api().post("/api/materials/" + id + "/approve", null).status())
        .isEqualTo(200);
    assertThat(s2.api().get("/api/materials/" + id).json().get("status").asString())
        .isEqualTo("published");
  }

  @Test
  void studentsAndModeratorsAddHomeworkAndMaterials() {
    long g = newGroup("Все пишут");
    TestUser headman = newUser(g, "headman");
    TestUser s1 = newUser(g, "student");
    TestUser s2 = newUser(g, "student");
    TestUser mod = newUser(newGroup("Чужая"), "student");
    admin().put("/api/admin/users/" + mod.id() + "/instance-role", Map.of("role", "moderator"));
    // Смена роли завершает сеансы — модератор входит заново.
    TestUser moderator = new TestUser(mod.id(), mod.username(), login(mod.username(), PASSWORD));
    long s = subject(g);

    // Студент сам выкладывает файл — сразу, без проверки старостой.
    long fileId = s1.api().upload("Конспект.pdf", PDF).json().get("id").asLong();
    var m =
        s1.api()
            .post("/api/subjects/" + s + "/materials", Map.of("kind", "file", "fileId", fileId));
    assertThat(m.status()).as(m.body()).isEqualTo(200);
    assertThat(m.json().get("status").asString()).isEqualTo("published");

    // И задание, и модератор сайта — тоже.
    long due = clock.millis() + 3 * 86_400_000L;
    for (TestUser who : List.of(s2, moderator)) {
      var hw =
          who.api()
              .post(
                  "/api/homework",
                  Map.of("subjectId", s, "title", "Прочитать главу", "dueAt", due));
      assertThat(hw.status()).as(hw.body()).isEqualTo(200);
    }

    // Папку с чужим файлом студент не удалит (файлы одногруппников), свою пустую — удалит.
    long folder =
        s2.api()
            .post("/api/subjects/" + s + "/folders", Map.of("name", "Лекции"))
            .json()
            .get("id")
            .asLong();
    assertThat(
            s1.api()
                .patch("/api/materials/" + m.json().get("id").asLong(), Map.of("folderId", folder))
                .status())
        .isEqualTo(200);
    assertThat(s2.api().delete("/api/folders/" + folder).status()).isEqualTo(403);
    assertThat(headman.api().delete("/api/folders/" + folder).status()).isEqualTo(200);
    long own =
        s2.api()
            .post("/api/subjects/" + s + "/folders", Map.of("name", "Моё"))
            .json()
            .get("id")
            .asLong();
    assertThat(s2.api().delete("/api/folders/" + own).status()).isEqualTo(200);
  }

  @Test
  void foldersAndBreadcrumbs() {
    long g = newGroup("Папки");
    TestUser deputy = newUser(g, "deputy");
    TestUser student = newUser(g, "student");
    long s = subject(g);
    long lectures =
        deputy
            .api()
            .post("/api/subjects/" + s + "/folders", Map.of("name", "Лекции"))
            .json()
            .get("id")
            .asLong();
    long week1 =
        deputy
            .api()
            .post(
                "/api/subjects/" + s + "/folders", Map.of("name", "Неделя 1", "parentId", lectures))
            .json()
            .get("id")
            .asLong();
    deputy
        .api()
        .post(
            "/api/subjects/" + s + "/materials",
            Map.of("kind", "link", "url", "https://example.org/a", "folderId", week1));
    var listing = student.api().get("/api/subjects/" + s + "/materials?folder=" + week1).json();
    assertThat(listing.get("path").size()).isEqualTo(2);
    assertThat(listing.get("path").get(0).get("name").asString()).isEqualTo("Лекции");
    assertThat(listing.get("materials").size()).isEqualTo(1);
    // Выкладывать и заводить папки студентам можно (с 0.4.12); свою пустую — и удалить.
    assertThat(listing.get("canUpload").asBoolean()).isTrue();
    var mine = student.api().post("/api/subjects/" + s + "/folders", Map.of("name", "Моя"));
    assertThat(mine.status()).isEqualTo(200);
    assertThat(student.api().delete("/api/folders/" + mine.json().get("id").asLong()).status())
        .isEqualTo(200);

    assertThat(deputy.api().delete("/api/folders/" + lectures).status()).isEqualTo(200);
    assertThat(student.api().get("/api/subjects/" + s + "/materials").json().get("folders").size())
        .isZero();
  }

  @Test
  void badInputsAreRejected() {
    long g = newGroup("Проверки");
    TestUser headman = newUser(g, "headman");
    long s = subject(g);
    byte[] elf = new byte[256];
    elf[0] = 0x7f;
    elf[1] = 'E';
    elf[2] = 'L';
    elf[3] = 'F';
    assertThat(headman.api().upload("notes.pdf", elf).status()).isEqualTo(415);
    assertThat(headman.api().upload("big.bin", new byte[1024 * 1024 + 10]).status()).isEqualTo(413);
    assertThat(
            headman
                .api()
                .post(
                    "/api/subjects/" + s + "/materials",
                    Map.of("kind", "link", "url", "javascript:alert(1)"))
                .status())
        .isEqualTo(400);
  }

  @Test
  void homeworkAttachmentsAreVisibleWithHomework() {
    long g = newGroup("Вложения");
    TestUser deputy = newUser(g, "deputy");
    TestUser student = newUser(g, "student");
    long s = subject(g);
    long fileId = deputy.api().upload("Условие.pdf", PDF).json().get("id").asLong();
    var hw =
        deputy
            .api()
            .post(
                "/api/homework",
                Map.of(
                    "subjectId",
                    s,
                    "title",
                    "Расчётка",
                    "dueAt",
                    clock.millis() + 86_400_000,
                    "attachments",
                    List.of(fileId)));
    assertThat(hw.status()).as(hw.body()).isEqualTo(200);
    assertThat(hw.json().get("attachments").get(0).get("name").asString()).isEqualTo("Условие.pdf");
    assertThat(student.api().download("/api/files/" + fileId).statusCode()).isEqualTo(200);
  }

  @Test
  void newsCarriesPhotosAndFiles() {
    long g = newGroup("Фото в новостях");
    TestUser deputy = newUser(g, "deputy");
    TestUser student = newUser(g, "student");
    TestUser stranger = newUser(newGroup("Чужие"), "student");
    long fileId = deputy.api().upload("Расписание.pdf", PDF).json().get("id").asLong();
    var news =
        deputy
            .api()
            .post(
                "/api/news",
                Map.of(
                    "title",
                    "Новое расписание",
                    "groupIds",
                    List.of(g),
                    "attachments",
                    List.of(fileId)));
    assertThat(news.status()).as(news.body()).isEqualTo(200);
    long id = news.json().get("id").asLong();
    assertThat(news.json().get("attachments").get(0).get("name").asString())
        .isEqualTo("Расписание.pdf");
    // Файл видят те, кто видит новость; чужим — нет.
    assertThat(student.api().download("/api/files/" + fileId).statusCode()).isEqualTo(200);
    assertThat(stranger.api().download("/api/files/" + fileId).statusCode()).isIn(403, 404);
    // Тот же файл ко второй новости не прикрепить.
    assertThat(
            deputy
                .api()
                .post(
                    "/api/news",
                    Map.of(
                        "title", "Ещё раз", "groupIds", List.of(g), "attachments", List.of(fileId)))
                .status())
        .isEqualTo(403);
    // Убрали вложения при правке — у новости их больше нет.
    var edited =
        deputy.api().patch("/api/news/" + id, Map.of("attachments", List.<Long>of())).json();
    assertThat(edited.get("attachments").size()).isZero();
  }

  @Test
  void textFilesAreSandboxed() {
    long g = newGroup("Песочница");
    TestUser headman = newUser(g, "headman");
    long s = subject(g);
    long id =
        headman
            .api()
            .upload("page.html", "<html><script>alert(1)</script></html>".getBytes())
            .json()
            .get("id")
            .asLong();
    headman.api().post("/api/subjects/" + s + "/materials", Map.of("kind", "file", "fileId", id));
    var r = headman.api().download("/api/files/" + id);
    assertThat(r.headers().firstValue("Content-Disposition").orElseThrow())
        .startsWith("attachment");
    assertThat(r.headers().firstValue("Content-Security-Policy").orElseThrow())
        .startsWith("sandbox");
  }

  @Autowired app.groupbase.files.FileStore store;

  @Test
  void missingFileOnDiskIsNotFoundNotServerError() throws IOException {
    long g = newGroup("Потерянный файл");
    TestUser headman = newUser(g, "headman");
    long s = subject(g);
    long id = headman.api().upload("lost.pdf", "%PDF-1.4 x".getBytes()).json().get("id").asLong();
    headman.api().post("/api/subjects/" + s + "/materials", Map.of("kind", "file", "fileId", id));
    Files.delete(store.path(store.find(id).orElseThrow().uuid()));
    var r = headman.api().download("/api/files/" + id);
    assertThat(r.statusCode()).isEqualTo(404);
    assertThat(new String(r.body(), StandardCharsets.UTF_8)).contains("file_missing");
  }

  @Test
  void phoneSendsBackAFileTheServerLost() throws IOException {
    long g = newGroup("Файл с телефона");
    TestUser headman = newUser(g, "headman");
    TestUser student = newUser(g, "student");
    TestUser stranger = newUser(newGroup("Чужая"), "student");
    long s = subject(g);
    byte[] content = "%PDF-1.4 конспект с телефона %%EOF".getBytes(StandardCharsets.UTF_8);
    long id = headman.api().upload("notes.pdf", content).json().get("id").asLong();
    headman.api().post("/api/subjects/" + s + "/materials", Map.of("kind", "file", "fileId", id));
    var ids = Map.of("ids", List.of(id));
    assertThat(student.api().post("/api/files/missing", ids).json().get("missing").size()).isZero();

    Files.delete(store.path(store.find(id).orElseThrow().uuid()));
    // Чужому не говорим, что файл вообще есть.
    assertThat(stranger.api().post("/api/files/missing", ids).json().get("missing").size())
        .isZero();
    assertThat(student.api().post("/api/files/missing", ids).json().get("missing").get(0).asLong())
        .isEqualTo(id);

    // Не тот файл — не принимаем; чужой — не находит.
    var wrong = student.api().putRaw("/api/files/" + id + "/content", "%PDF-1.4 другое".getBytes());
    assertThat(wrong.status()).isEqualTo(409);
    assertThat(stranger.api().putRaw("/api/files/" + id + "/content", content).status())
        .isEqualTo(404);

    var ok = student.api().putRaw("/api/files/" + id + "/content", content);
    assertThat(ok.status()).as(ok.body()).isEqualTo(200);
    assertThat(ok.json().get("restored").asBoolean()).isTrue();
    var r = headman.api().download("/api/files/" + id);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).isEqualTo(content);
    // Второй раз — уже на месте.
    var again = student.api().putRaw("/api/files/" + id + "/content", content);
    assertThat(again.json().get("restored").asBoolean()).isFalse();
  }

  @Test
  void notesArePastedAndPinnedOnTop() {
    long g = newGroup("Сообщения");
    TestUser headman = newUser(g, "headman");
    TestUser student = newUser(g, "student");
    long s = subject(g);
    var link =
        headman
            .api()
            .post(
                "/api/subjects/" + s + "/materials",
                Map.of("kind", "link", "url", "https://example.org/book"));
    assertThat(link.status()).as(link.body()).isEqualTo(200);
    // Сообщение из чата: название — первая строка.
    var note =
        student
            .api()
            .post(
                "/api/subjects/" + s + "/materials",
                Map.of("kind", "note", "description", "Билеты к экзамену\n1. Пределы\n2. Ряды"));
    assertThat(note.status()).as(note.body()).isEqualTo(200);
    long noteId = note.json().get("id").asLong();
    assertThat(student.api().get("/api/materials/" + noteId).json().get("title").asString())
        .isEqualTo("Билеты к экзамену");
    assertThat(
            student
                .api()
                .post(
                    "/api/subjects/" + s + "/materials", Map.of("kind", "note", "description", " "))
                .status())
        .isEqualTo(400);

    // Закрепляет староста, студент — нет; закреплённое — первым в списке.
    assertThat(
            student
                .api()
                .put("/api/materials/" + noteId + "/pinned", Map.of("pinned", true))
                .status())
        .isEqualTo(403);
    long linkId = link.json().get("id").asLong();
    var pinned = headman.api().put("/api/materials/" + linkId + "/pinned", Map.of("pinned", true));
    assertThat(pinned.status()).as(pinned.body()).isEqualTo(200);
    assertThat(pinned.json().get("pinnedAt").isNull()).isFalse();
    var list = student.api().get("/api/subjects/" + s + "/materials").json().get("materials");
    assertThat(list.get(0).get("id").asLong()).isEqualTo(linkId);
    assertThat(list.get(0).get("can").get("pin").asBoolean()).isFalse();
    // Поиск находит сообщение по тексту.
    assertThat(student.api().get("/api/search?q=Ряды").body()).contains("Билеты к экзамену");
  }

  @Test
  void markdownNotesOpenAsPageAndDownloadAsWord() {
    long g = newGroup("Конспекты");
    TestUser headman = newUser(g, "headman");
    TestUser stranger = newUser(newGroup("Чужие конспекты"), "student");
    long s = subject(g);
    byte[] md = "# Лекция 1\n\nТеорема **Ферма**.".getBytes(StandardCharsets.UTF_8);
    long id = headman.api().upload("Лекция 1.md", md).json().get("id").asLong();
    headman.api().post("/api/subjects/" + s + "/materials", Map.of("kind", "file", "fileId", id));

    var html = headman.api().get("/api/files/" + id + "/html");
    assertThat(html.status()).as(html.body()).isEqualTo(200);
    assertThat(html.json().get("html").asString())
        .contains("<h1>Лекция 1</h1>", "<strong>Ферма</strong>");

    var word = headman.api().download("/api/files/" + id + "/docx");
    assertThat(word.statusCode()).isEqualTo(200);
    assertThat(word.headers().firstValue("Content-Disposition").orElseThrow()).contains(".docx");
    assertThat(new String(word.body(), 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");

    // Чужой не видит; не Markdown — не превращается.
    assertThat(stranger.api().get("/api/files/" + id + "/html").status()).isEqualTo(404);
    long pdf =
        headman.api().upload("a.pdf", "%PDF-1.4 x %%EOF".getBytes()).json().get("id").asLong();
    headman.api().post("/api/subjects/" + s + "/materials", Map.of("kind", "file", "fileId", pdf));
    assertThat(headman.api().get("/api/files/" + pdf + "/docx").status()).isEqualTo(400);
  }
}
