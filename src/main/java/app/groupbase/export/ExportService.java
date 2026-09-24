package app.groupbase.export;

import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.config.GroupbaseProperties;
import app.groupbase.files.FileStore;
import app.groupbase.files.StoredFile;
import app.groupbase.web.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Выгрузка в ZIP: «мои данные» для любого пользователя и архив группы для старосты. Архив читается
 * без groupbase: CSV открывается в Excel, тексты — в Блокноте (.md), ссылки — двойным щелчком
 * (.url); рядом data.json для программ.
 */
@Service
public class ExportService {

  private static final Map<Object, String> KINDS =
      Map.of(
          "homework", "Домашнее задание",
          "lab", "Лабораторная",
          "test", "Контрольная",
          "credit", "Зачёт",
          "exam", "Экзамен");

  static final Locale RU = Locale.forLanguageTag("ru");
  private static final DateTimeFormatter HUMAN =
      DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", RU);
  private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
  private static final Map<String, String> ROLES =
      Map.of("headman", "староста", "deputy", "заместитель", "student", "студент");
  private static final Map<String, String> STATUS =
      Map.of(
          "active", "активен",
          "pending", "не активирован",
          "blocked", "заблокирован",
          "deleted", "удалён");

  private final JdbcClient db;
  private final FileStore files;
  private final AuditService audit;
  private final GroupbaseProperties props;
  private final Clock clock;
  private final JsonMapper json =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  public ExportService(
      JdbcClient db, FileStore files, AuditService audit, GroupbaseProperties props, Clock clock) {
    this.db = db;
    this.files = files;
    this.audit = audit;
    this.props = props;
    this.clock = clock;
  }

  String human(long ms) {
    return HUMAN.format(Instant.ofEpochMilli(ms).atZone(props.timezone()));
  }

  public String day(long ms) {
    return DAY.format(Instant.ofEpochMilli(ms).atZone(props.timezone()));
  }

  private static String person(String name, String status) {
    return "deleted".equals(status) || name == null || name.isBlank()
        ? "удалённый пользователь"
        : name;
  }

  // ---------- мои данные ----------

  public void user(Actor actor, OutputStream out) throws IOException {
    long uid = actor.id();
    long now = clock.millis();
    Map<String, Object> profile =
        db.sql(
                """
                SELECT id, username, display_name, instance_role, status, totp_enabled, created_at
                FROM users WHERE id = ?
                """)
            .param(uid)
            .query(
                (rs, i) -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("id", rs.getLong("id"));
                  m.put("логин", rs.getString("username"));
                  m.put("ФИО", rs.getString("display_name"));
                  m.put("роль на сайте", rs.getString("instance_role"));
                  m.put("двухфакторная защита", rs.getInt("totp_enabled") == 1);
                  m.put("аккаунт создан", human(rs.getLong("created_at")));
                  return m;
                })
            .single();
    List<Map<String, Object>> groups =
        db.sql(
                """
                SELECT g.name, g.university, m.role, m.joined_at FROM memberships m
                JOIN study_groups g ON g.id = m.group_id WHERE m.user_id = ? ORDER BY g.name
                """)
            .param(uid)
            .query(
                (rs, i) -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("группа", rs.getString("name"));
                  m.put("вуз", rs.getString("university"));
                  m.put("роль", ROLES.getOrDefault(rs.getString("role"), rs.getString("role")));
                  m.put("в группе с", human(rs.getLong("joined_at")));
                  return m;
                })
            .list();
    profile.put("группы", groups);

    List<List<String>> done = new ArrayList<>();
    done.add(List.of("Задание", "Предмет", "Срок", "Отмечено выполненным"));
    db.sql(
            """
            SELECT h.title, s.name AS subject, h.due_at, d.done_at FROM homework_done d
            JOIN homework h ON h.id = d.homework_id JOIN subjects s ON s.id = h.subject_id
            WHERE d.user_id = ? ORDER BY d.done_at
            """)
        .param(uid)
        .query(
            rs -> {
              done.add(
                  List.of(
                      rs.getString("title"),
                      rs.getString("subject"),
                      human(rs.getLong("due_at")),
                      human(rs.getLong("done_at"))));
            });

    List<List<String>> comments = new ArrayList<>();
    comments.add(List.of("Где", "К чему", "Когда", "Текст"));
    db.sql(
            """
            SELECT c.target_type, c.body_md, c.created_at,
              CASE c.target_type
                WHEN 'post' THEN (SELECT title FROM posts WHERE id = c.target_id)
                WHEN 'homework' THEN (SELECT title FROM homework WHERE id = c.target_id)
                ELSE (SELECT title FROM materials WHERE id = c.target_id) END AS target_title
            FROM comments c WHERE c.author_id = ? ORDER BY c.id
            """)
        .param(uid)
        .query(
            rs -> {
              String where =
                  switch (rs.getString("target_type")) {
                    case "post" -> "новость";
                    case "homework" -> "задание";
                    default -> "материал";
                  };
              comments.add(
                  List.of(
                      where,
                      String.valueOf(rs.getString("target_title")),
                      human(rs.getLong("created_at")),
                      rs.getString("body_md")));
            });

    List<List<String>> actions = new ArrayList<>();
    actions.add(List.of("Когда", "Действие", "Объект", "IP-адрес"));
    db.sql(
            "SELECT at, action, target_type, target_id, ip FROM audit_log"
                + " WHERE actor_id = ? ORDER BY id")
        .param(uid)
        .query(
            rs -> {
              String type = rs.getString("target_type");
              long id = rs.getLong("target_id");
              actions.add(
                  List.of(
                      human(rs.getLong("at")),
                      rs.getString("action"),
                      type == null ? "" : type + (rs.wasNull() ? "" : " #" + id),
                      rs.getString("ip") == null ? "" : rs.getString("ip")));
            });

    try (ZipWriter zip = new ZipWriter(out)) {
      zip.text(
          "Прочитайте меня.txt",
          """
          Ваши данные из groupbase, выгружены %s.

          • Профиль.json — логин, ФИО, группы и роли;
          • Выполненные задания.csv — ваши отметки «сделано» (открывается в Excel);
          • Мои комментарии.csv и Мои действия.csv — что вы писали и делали на сайте;
          • Мои публикации — новости и задания, которые вы выложили (.md открывается в Блокноте);
          • Мои файлы — файлы, которые вы загрузили.
          """
              .formatted(human(now)),
          now);
      zip.text("Профиль.json", json.writeValueAsString(profile), now);
      zip.text("Выполненные задания.csv", ZipWriter.csv(done), now);
      zip.text("Мои комментарии.csv", ZipWriter.csv(comments), now);
      zip.text("Мои действия.csv", ZipWriter.csv(actions), now);

      for (var p :
          db.sql(
                  """
                  SELECT p.title, p.body_md, p.created_at, s.name AS subject FROM posts p
                  LEFT JOIN subjects s ON s.id = p.subject_id WHERE p.author_id = ? ORDER BY p.id
                  """)
              .param(uid)
              .query(
                  (rs, i) ->
                      doc(
                          rs.getString("title"),
                          rs.getString("body_md"),
                          rs.getLong("created_at"),
                          rs.getString("subject")))
              .list()) {
        zip.text(
            ZipWriter.path("Мои публикации", "Новости", day(p.at()) + " " + p.title()) + ".md",
            p.markdown(this, "Опубликовано"),
            p.at());
      }
      for (var h :
          db.sql(
                  """
                  SELECT h.title, h.body_md, h.due_at, s.name AS subject FROM homework h
                  JOIN subjects s ON s.id = h.subject_id WHERE h.author_id = ? ORDER BY h.id
                  """)
              .param(uid)
              .query(
                  (rs, i) ->
                      doc(
                          rs.getString("title"),
                          rs.getString("body_md"),
                          rs.getLong("due_at"),
                          rs.getString("subject")))
              .list()) {
        zip.text(
            ZipWriter.path("Мои публикации", "Задания", day(h.at()) + " " + h.title()) + ".md",
            h.markdown(this, "Срок"),
            now);
      }
      for (Long id :
          db.sql("SELECT id FROM files WHERE uploaded_by = ? ORDER BY id")
              .param(uid)
              .query(Long.class)
              .list()) {
        StoredFile f = files.find(id).orElse(null);
        if (f != null) {
          try (InputStream in = files.open(f)) {
            zip.file(ZipWriter.path("Мои файлы", f.name()), in, f.createdAt());
          }
        }
      }
    }
    audit.log(actor, null, "export.user", "user", uid);
  }

  private record Doc(String title, String body, long at, String subject) {
    String markdown(ExportService s, String dateLabel) {
      StringBuilder b = new StringBuilder("# ").append(title).append("\n\n");
      if (subject != null) {
        b.append("- Предмет: ").append(subject).append('\n');
      }
      b.append("- ").append(dateLabel).append(": ").append(s.human(at)).append("\n\n");
      return b.append(body == null ? "" : body).append('\n').toString();
    }
  }

  private static Doc doc(String title, String body, long at, String subject) {
    return new Doc(title, body, at, subject);
  }

  // ---------- группа ----------

  private record Folder(long id, Long parent, String name) {}

  public String groupName(long groupId) {
    return db.sql("SELECT name FROM study_groups WHERE id = ?")
        .param(groupId)
        .query(String.class)
        .optional()
        .orElseThrow(ApiException::notFound);
  }

  public void group(Actor actor, long groupId, OutputStream out) throws IOException {
    long now = clock.millis();
    Map<String, Object> group =
        db.sql("SELECT id, name, university, course FROM study_groups WHERE id = ?")
            .param(groupId)
            .query(
                (rs, i) -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("id", rs.getLong("id"));
                  m.put("name", rs.getString("name"));
                  m.put("university", rs.getString("university"));
                  int course = rs.getInt("course");
                  m.put("course", rs.wasNull() ? null : course);
                  return m;
                })
            .optional()
            .orElseThrow(ApiException::notFound);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("exportedAt", now);
    data.put("group", group);

    List<Map<String, Object>> members =
        db.sql(
                """
                SELECT u.id, u.display_name, u.username, u.status, m.role, m.joined_at
                FROM memberships m JOIN users u ON u.id = m.user_id
                WHERE m.group_id = ? ORDER BY u.display_name COLLATE NOCASE
                """)
            .param(groupId)
            .query(
                (rs, i) -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("id", rs.getLong("id"));
                  m.put("name", person(rs.getString("display_name"), rs.getString("status")));
                  m.put("username", rs.getString("username"));
                  m.put("role", rs.getString("role"));
                  m.put("status", rs.getString("status"));
                  m.put("joinedAt", rs.getLong("joined_at"));
                  return m;
                })
            .list();
    data.put("members", members);
    List<List<String>> memberRows = new ArrayList<>();
    memberRows.add(List.of("ФИО", "Логин", "Роль", "Статус", "В группе с"));
    for (Map<String, Object> m : members) {
      memberRows.add(
          List.of(
              (String) m.get("name"),
              (String) m.get("username"),
              ROLES.getOrDefault((String) m.get("role"), (String) m.get("role")),
              STATUS.getOrDefault((String) m.get("status"), (String) m.get("status")),
              human((Long) m.get("joinedAt"))));
    }

    try (ZipWriter zip = new ZipWriter(out)) {
      String name = (String) group.get("name");
      zip.text(
          "Прочитайте меня.txt",
          """
          Архив группы «%s», выгружен %s.

          • Участники.csv — список группы (открывается в Excel);
          • Новости — каждая новость отдельным файлом .md (это обычный текст, открывается в Блокноте);
          • Предметы — по папке на предмет: задания с приложенными файлами и материалы;
          • файлы .url — ссылки, открываются двойным щелчком;
          • data.json — всё то же самое для программ.
          """
              .formatted(name, human(now)),
          now);
      zip.text("Участники.csv", ZipWriter.csv(memberRows), now);

      List<Map<String, Object>> news = new ArrayList<>();
      for (Map<String, Object> p :
          db.sql(
                  """
                  SELECT p.id, p.title, p.body_md, p.pinned, p.urgent, p.hidden, p.created_at,
                    a.display_name AS author, a.status AS author_status, s.name AS subject
                  FROM posts p JOIN post_targets t ON t.post_id = p.id AND t.group_id = ?
                  LEFT JOIN users a ON a.id = p.author_id LEFT JOIN subjects s ON s.id = p.subject_id
                  ORDER BY p.id
                  """)
              .param(groupId)
              .query((rs, i) -> row(rs))
              .list()) {
        long at = (Long) p.get("created_at");
        StringBuilder b = new StringBuilder("# ").append(p.get("title")).append("\n\n");
        b.append("- Автор: ")
            .append(person((String) p.get("author"), (String) p.get("author_status")))
            .append(", ")
            .append(human(at))
            .append('\n');
        if (p.get("subject") != null) {
          b.append("- Предмет: ").append(p.get("subject")).append('\n');
        }
        flags(b, p);
        b.append('\n').append(p.get("body_md")).append('\n');
        comments(b, "post", (Long) p.get("id"));
        zip.text(
            ZipWriter.path("Новости", day(at) + " " + p.get("title")) + ".md", b.toString(), at);
        news.add(clean(p));
      }
      data.put("news", news);

      List<Map<String, Object>> subjects = new ArrayList<>();
      for (Map<String, Object> s :
          db.sql(
                  """
                  SELECT s.id, s.name, s.teacher, s.archived_at FROM subjects s
                  JOIN subject_groups sg ON sg.subject_id = s.id AND sg.group_id = ?
                  ORDER BY s.name COLLATE NOCASE
                  """)
              .param(groupId)
              .query((rs, i) -> row(rs))
              .list()) {
        subjects.add(subject(zip, groupId, s));
      }
      data.put("subjects", subjects);
      zip.text("data.json", json.writeValueAsString(data), now);
    }
    audit.log(actor, groupId, "export.group", "group", groupId);
  }

  private Map<String, Object> subject(ZipWriter zip, long groupId, Map<String, Object> s)
      throws IOException {
    long sid = (Long) s.get("id");
    String dir = ZipWriter.safe((String) s.get("name"));
    StringBuilder about = new StringBuilder("# ").append(s.get("name")).append("\n\n");
    if (!String.valueOf(s.get("teacher")).isBlank()) {
      about.append("- Преподаватель: ").append(s.get("teacher")).append('\n');
    }
    if (s.get("archived_at") != null) {
      about.append("- В архиве\n");
    }
    zip.text("Предметы/" + dir + "/О предмете.md", about.toString(), clock.millis());

    List<Map<String, Object>> homework = new ArrayList<>();
    for (Map<String, Object> h :
        db.sql(
                """
                SELECT h.id, h.title, h.body_md, h.due_at, h.kind, h.place, h.difficulty,
                  h.hidden, h.created_at, a.display_name AS author, a.status AS author_status
                FROM homework h JOIN homework_targets t ON t.homework_id = h.id AND t.group_id = ?
                LEFT JOIN users a ON a.id = h.author_id
                WHERE h.subject_id = ? ORDER BY h.due_at
                """)
            .params(groupId, sid)
            .query((rs, i) -> row(rs))
            .list()) {
      long due = (Long) h.get("due_at");
      String base = ZipWriter.safe(day(due) + " " + h.get("title"));
      StringBuilder b = new StringBuilder("# ").append(h.get("title")).append("\n\n");
      b.append("- Предмет: ").append(s.get("name")).append('\n');
      b.append("- Тип: ").append(KINDS.getOrDefault(h.get("kind"), "Задание")).append('\n');
      b.append("- Срок: ").append(human(due)).append('\n');
      if (h.get("place") instanceof String place && !place.isBlank()) {
        b.append("- Место: ").append(place).append('\n');
      }
      if (h.get("difficulty") instanceof Number d) {
        b.append("- Сложность: ")
            .append(
                switch (d.intValue()) {
                  case 1 -> "легко";
                  case 2 -> "средне";
                  default -> "сложно";
                })
            .append('\n');
      }
      b.append("- Автор: ")
          .append(person((String) h.get("author"), (String) h.get("author_status")))
          .append(", ")
          .append(human((Long) h.get("created_at")))
          .append('\n');
      flags(b, h);
      b.append('\n').append(h.get("body_md")).append('\n');
      List<Long> attached =
          db.sql("SELECT file_id FROM homework_attachments WHERE homework_id = ? ORDER BY position")
              .param(h.get("id"))
              .query(Long.class)
              .list();
      List<String> names = new ArrayList<>();
      for (Long fid : attached) {
        StoredFile f = files.find(fid).orElse(null);
        if (f != null) {
          try (InputStream in = files.open(f)) {
            String p =
                zip.file(
                    "Предметы/" + dir + "/Задания/" + base + " — файлы/" + ZipWriter.safe(f.name()),
                    in,
                    f.createdAt());
            names.add(p.substring(p.lastIndexOf('/') + 1));
          }
        }
      }
      if (!names.isEmpty()) {
        b.append("\n## Файлы\n\nВ папке «").append(base).append(" — файлы»:\n\n");
        names.forEach(n -> b.append("- ").append(n).append('\n'));
      }
      comments(b, "homework", (Long) h.get("id"));
      zip.text(
          "Предметы/" + dir + "/Задания/" + base + ".md", b.toString(), (Long) h.get("created_at"));
      Map<String, Object> clean = clean(h);
      clean.put("files", names);
      homework.add(clean);
    }

    Map<Long, Folder> folders = new HashMap<>();
    db.sql("SELECT id, parent_id, name FROM folders WHERE subject_id = ?")
        .param(sid)
        .query(
            rs -> {
              long parent = rs.getLong("parent_id");
              folders.put(
                  rs.getLong("id"),
                  new Folder(rs.getLong("id"), rs.wasNull() ? null : parent, rs.getString("name")));
            });
    List<Map<String, Object>> materials = new ArrayList<>();
    for (Map<String, Object> m :
        db.sql(
                """
                SELECT id, folder_id, kind, title, description, url, file_id, hidden, created_at
                FROM materials WHERE subject_id = ? AND status = 'published' ORDER BY id
                """)
            .param(sid)
            .query((rs, i) -> row(rs))
            .list()) {
      String folder = folderPath(folders, (Long) m.get("folder_id"));
      String dirPath = "Предметы/" + dir + "/Материалы" + (folder.isEmpty() ? "" : "/" + folder);
      long at = (Long) m.get("created_at");
      String title = (String) m.get("title");
      if ("link".equals(m.get("kind"))) {
        zip.text(
            dirPath + "/" + ZipWriter.safe(title) + ".url",
            "[InternetShortcut]\r\nURL=" + m.get("url") + "\r\n",
            at);
      } else if (m.get("file_id") != null) {
        StoredFile f = files.find((Long) m.get("file_id")).orElse(null);
        if (f != null) {
          try (InputStream in = files.open(f)) {
            zip.file(dirPath + "/" + fileName(title, f.name()), in, at);
          }
        }
      }
      Map<String, Object> clean = clean(m);
      clean.put("folder", folder);
      materials.add(clean);
    }

    Map<String, Object> out = clean(s);
    out.put("homework", homework);
    out.put("materials", materials);
    return out;
  }

  /**
   * Название материала с расширением исходного файла: «Лекция 3» + «конспект.pdf» → «Лекция 3.pdf».
   */
  static String fileName(String title, String original) {
    int dot = original.lastIndexOf('.');
    String ext = dot > 0 ? original.substring(dot) : "";
    String t = ZipWriter.safe(title);
    return ext.isEmpty() || t.toLowerCase(RU).endsWith(ext.toLowerCase(RU)) ? t : t + ext;
  }

  private static String folderPath(Map<Long, Folder> folders, Long id) {
    List<String> parts = new ArrayList<>();
    for (int depth = 0; id != null && depth < 20; depth++) {
      Folder f = folders.get(id);
      if (f == null) {
        break;
      }
      parts.addFirst(ZipWriter.safe(f.name()));
      id = f.parent();
    }
    return String.join("/", parts);
  }

  private static void flags(StringBuilder b, Map<String, Object> row) {
    if (flag(row.get("urgent"))) {
      b.append("- Срочно\n");
    }
    if (flag(row.get("pinned"))) {
      b.append("- Закреплено\n");
    }
    if (flag(row.get("hidden"))) {
      b.append("- Скрыто модератором\n");
    }
  }

  private void comments(StringBuilder b, String type, long id) {
    List<String> lines =
        db.sql(
                """
                SELECT a.display_name, a.status, c.body_md, c.created_at FROM comments c
                LEFT JOIN users a ON a.id = c.author_id
                WHERE c.target_type = ? AND c.target_id = ? AND c.hidden = 0 ORDER BY c.id
                """)
            .params(type, id)
            .query(
                (rs, i) ->
                    "**"
                        + person(rs.getString("display_name"), rs.getString("status"))
                        + "**, "
                        + human(rs.getLong("created_at"))
                        + ":\n\n"
                        + rs.getString("body_md")
                        + "\n")
            .list();
    if (!lines.isEmpty()) {
      b.append("\n## Комментарии\n\n").append(String.join("\n", lines));
    }
  }

  /** Строка как словарь; целые всегда Long (sqlite-jdbc отдаёт маленькие числа как Integer). */
  private static Map<String, Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
    var meta = rs.getMetaData();
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 1; i <= meta.getColumnCount(); i++) {
      Object v = rs.getObject(i);
      m.put(meta.getColumnLabel(i), v instanceof Integer n ? Long.valueOf(n) : v);
    }
    return m;
  }

  private static boolean flag(Object v) {
    return v instanceof Number n && n.intValue() == 1;
  }

  /** Для data.json: без служебных полей автора. */
  private static Map<String, Object> clean(Map<String, Object> row) {
    Map<String, Object> m = new LinkedHashMap<>(row);
    Object status = m.remove("author_status");
    if (m.containsKey("author")) {
      m.put("author", person((String) m.get("author"), (String) status));
    }
    return m;
  }
}
