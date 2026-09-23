package app.groupbase.export;

import static org.assertj.core.api.Assertions.assertThat;

import app.groupbase.ApiClient;
import app.groupbase.IntegrationTest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ExportIT extends IntegrationTest {

  private static final byte[] PDF =
      ("%PDF-1.4\n% ряды Фурье\n" + "x".repeat(3000) + "\n%%EOF\n")
          .getBytes(StandardCharsets.UTF_8);

  static Map<String, byte[]> unzip(byte[] zip) throws IOException {
    Map<String, byte[]> out = new LinkedHashMap<>();
    try (ZipInputStream in =
        new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
      for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
        out.put(e.getName(), in.readAllBytes());
      }
    }
    return out;
  }

  static String text(Map<String, byte[]> files, String name) {
    assertThat(files).as("в архиве есть " + name).containsKey(name);
    return new String(files.get(name), StandardCharsets.UTF_8);
  }

  static String find(Map<String, byte[]> files, String prefix) {
    return files.keySet().stream()
        .filter(n -> n.startsWith(prefix))
        .findFirst()
        .orElseThrow(() -> new AssertionError("нет файла " + prefix + " среди " + files.keySet()));
  }

  @Test
  void headmanDownloadsReadableGroupArchive() throws Exception {
    long g = newGroup("Архив");
    long other = newGroup("Чужая");
    TestUser headman = newUser(g, "headman");
    TestUser deputy = newUser(g, "deputy");
    TestUser student = newUser(g, "student");
    TestUser strangerHead = newUser(other, "headman");
    ApiClient h = headman.api();

    long s =
        h.post(
                "/api/groups/" + g + "/subjects",
                Map.of("name", "Физика", "teacher", "Сидоров П. П."))
            .json()
            .get("id")
            .asLong();
    long attachment = h.upload("Условия.pdf", PDF).json().get("id").asLong();
    long hw =
        h.post(
                "/api/homework",
                Map.of(
                    "subjectId",
                    s,
                    "title",
                    "Лабораторная: маятник",
                    "body",
                    "Измерить **период**",
                    "dueAt",
                    clock.millis() + 86_400_000L,
                    "groupIds",
                    List.of(g),
                    "attachments",
                    List.of(attachment)))
            .json()
            .get("id")
            .asLong();
    long news =
        h.post(
                "/api/news",
                Map.of(
                    "title",
                    "Перенос пары",
                    "body",
                    "В 314",
                    "urgent",
                    true,
                    "groupIds",
                    List.of(g)))
            .json()
            .get("id")
            .asLong();
    student.api().post("/api/news/" + news + "/comments", Map.of("body", "Спасибо!"));
    student.api().put("/api/homework/" + hw + "/done", Map.of("value", true));
    long folder =
        h.post("/api/subjects/" + s + "/folders", Map.of("name", "Лекции"))
            .json()
            .get("id")
            .asLong();
    long file = h.upload("конспект.pdf", PDF).json().get("id").asLong();
    h.post(
        "/api/subjects/" + s + "/materials",
        Map.of("kind", "file", "fileId", file, "title", "Лекция 1", "folderId", folder));
    h.post(
        "/api/subjects/" + s + "/materials",
        Map.of("kind", "link", "url", "https://example.org/book", "title", "Учебник"));

    String path = "/api/groups/" + g + "/export";
    assertThat(student.api().download(path).statusCode()).isEqualTo(403);
    assertThat(deputy.api().download(path).statusCode()).isEqualTo(403);
    assertThat(strangerHead.api().download(path).statusCode()).isEqualTo(403);
    assertThat(admin().download(path).statusCode()).isEqualTo(200);

    HttpResponse<byte[]> r = h.download(path);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.headers().firstValue("Content-Type")).hasValue("application/zip");
    assertThat(r.headers().firstValue("Content-Disposition").orElseThrow())
        .startsWith("attachment;")
        .contains("filename*=UTF-8''");
    Map<String, byte[]> files = unzip(r.body());

    assertThat(text(files, "Прочитайте меня.txt")).contains("Архив группы");
    String members = text(files, "Участники.csv");
    assertThat(members).startsWith("﻿ФИО;Логин;Роль;Статус;В группе с");
    assertThat(members).contains(student.username()).contains("староста");

    String newsDoc = text(files, find(files, "Новости/"));
    assertThat(newsDoc).contains("# Перенос пары").contains("- Срочно").contains("Спасибо!");

    String hwName =
        files.keySet().stream()
            .filter(n -> n.startsWith("Предметы/Физика/Задания/") && n.endsWith(".md"))
            .findFirst()
            .orElseThrow();
    assertThat(hwName).as("двоеточие в имени файла заменено").endsWith(" Лабораторная_ маятник.md");
    assertThat(text(files, hwName))
        .contains("# Лабораторная: маятник")
        .contains("Измерить **период**")
        .contains("Условия.pdf");
    String attached =
        files.keySet().stream()
            .filter(n -> n.endsWith(" — файлы/Условия.pdf"))
            .findFirst()
            .orElseThrow();
    assertThat(files.get(attached)).isEqualTo(PDF);

    assertThat(files.get("Предметы/Физика/Материалы/Лекции/Лекция 1.pdf")).isEqualTo(PDF);
    assertThat(text(files, "Предметы/Физика/Материалы/Учебник.url"))
        .contains("URL=https://example.org/book");
    assertThat(text(files, "Предметы/Физика/О предмете.md")).contains("Сидоров П. П.");

    var data = JsonMapper.builder().build().readTree(files.get("data.json"));
    assertThat(data.get("group").get("id").asLong()).isEqualTo(g);
    assertThat(data.get("members").size()).isEqualTo(3);
    assertThat(data.get("subjects").get(0).get("homework").get(0).get("files").get(0).asString())
        .isEqualTo("Условия.pdf");

    var audit = h.get("/api/groups/" + g + "/audit").json();
    assertThat(audit.toString()).contains("export.group");
  }

  @Test
  void everyoneDownloadsOwnData() throws Exception {
    long g = newGroup("Мои данные");
    TestUser headman = newUser(g, "headman");
    TestUser student = newUser(g, "student");
    long s =
        headman
            .api()
            .post("/api/groups/" + g + "/subjects", Map.of("name", "Химия"))
            .json()
            .get("id")
            .asLong();
    long hw =
        headman
            .api()
            .post(
                "/api/homework",
                Map.of(
                    "subjectId",
                    s,
                    "title",
                    "Реферат",
                    "body",
                    "",
                    "dueAt",
                    clock.millis() + 86_400_000L,
                    "groupIds",
                    List.of(g)))
            .json()
            .get("id")
            .asLong();
    student.api().put("/api/homework/" + hw + "/done", Map.of("value", true));
    student.api().post("/api/homework/" + hw + "/comments", Map.of("body", "Какой объём?"));
    headman.api().upload("мой-файл.pdf", PDF);

    Map<String, byte[]> mine = unzip(student.api().download("/api/me/export").body());
    assertThat(text(mine, "Профиль.json")).contains(student.username()).contains("Мои данные");
    assertThat(text(mine, "Выполненные задания.csv")).contains("Реферат").contains("Химия");
    assertThat(text(mine, "Мои комментарии.csv")).contains("Какой объём?");
    assertThat(text(mine, "Мои действия.csv")).startsWith("﻿Когда;Действие");
    assertThat(mine.keySet()).noneMatch(n -> n.startsWith("Мои файлы/"));

    Map<String, byte[]> head = unzip(headman.api().download("/api/me/export").body());
    assertThat(head.get("Мои файлы/мой-файл.pdf")).isEqualTo(PDF);
    assertThat(head.keySet())
        .anyMatch(n -> n.startsWith("Мои публикации/Задания/") && n.endsWith("Реферат.md"));
  }
}
