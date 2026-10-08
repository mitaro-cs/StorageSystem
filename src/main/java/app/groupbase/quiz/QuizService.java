package app.groupbase.quiz;

import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Permission;
import app.groupbase.content.Access;
import app.groupbase.content.SubjectService;
import app.groupbase.content.SubjectStore;
import app.groupbase.web.ApiException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Тесты онлайн по предмету (0.9.7, просьба владельца). Создают и смотрят результаты те, кто ведёт
 * предмет (MANAGE_SUBJECTS во всех его группах), проходят – все, кто видит предмет. Верные ответы
 * хранит и проверяет сервер: в браузер студента они приходят только после его попытки, если автор
 * теста это разрешил.
 */
@Service
public class QuizService {

  static final int MAX_QUESTIONS = 100;
  static final int MAX_OPTIONS = 10;

  /** Запас к ограничению времени: ответы, отправленные чуть позже (сеть), ещё принимаются. */
  static final long GRACE_MS = 60_000;

  /**
   * Вопрос. kind: single – один верный вариант, multi – несколько, text – ответ словом (верные
   * написания – accepted, без учёта регистра, пробелов по краям и «ё»).
   */
  public record Question(
      String kind,
      String text,
      List<String> options,
      List<Integer> correct,
      List<String> accepted,
      Integer points) {}

  /** Вопрос для проходящего – без верных ответов. */
  public record Ask(String kind, String text, List<String> options, int points) {}

  public record Answer(List<Integer> choices, String text) {}

  public record Input(
      String title,
      String description,
      List<Question> questions,
      Integer timeLimit,
      Integer attempts,
      Boolean showAnswers,
      Boolean published,
      Long closesAt) {}

  public record Can(boolean edit) {}

  /**
   * Мои попытки: сколько использовано, лучший результат, начатая и не законченная, последняя
   * законченная (открыть её разбор).
   */
  public record Mine(int used, Double best, Double max, Long open, Long last) {}

  /** Для ведущих: сколько человек прошли и средний процент. */
  public record Stats(int people, Double average) {}

  public record Item(
      long id,
      long subjectId,
      String title,
      String description,
      int questions,
      double points,
      Integer timeLimit,
      int attempts,
      boolean showAnswers,
      boolean published,
      Long closesAt,
      long createdAt,
      Mine mine,
      Stats stats,
      Can can) {}

  /** Тест целиком: ведущим – с верными ответами, остальным – только вопросы. */
  public record Detail(Item quiz, List<Question> questions, List<Ask> asks) {}

  public record Attempt(
      long id, long quizId, String title, long startedAt, Long deadline, List<Ask> questions) {}

  /** Разбор одного вопроса после попытки. */
  public record Mark(
      boolean right, double points, double max, List<Integer> correct, List<String> accepted) {}

  public record Result(
      long id,
      long quizId,
      String title,
      long startedAt,
      long finishedAt,
      double score,
      double max,
      List<Ask> questions,
      List<Answer> answers,
      List<Mark> marks) {}

  public record Row(
      long attemptId,
      long userId,
      String name,
      long startedAt,
      Long finishedAt,
      Double score,
      Double max) {}

  private record Quiz(
      long id,
      long subjectId,
      String title,
      String description,
      List<Question> questions,
      Integer timeLimit,
      int attempts,
      boolean showAnswers,
      boolean published,
      Long closesAt,
      long createdAt) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<List<Question>> QUESTIONS = new TypeReference<>() {};
  private static final TypeReference<List<Answer>> ANSWERS = new TypeReference<>() {};

  private final JdbcClient db;
  private final SubjectService subjects;
  private final SubjectStore store;
  private final Access access;
  private final AuditService audit;
  private final Clock clock;

  public QuizService(
      JdbcClient db,
      SubjectService subjects,
      SubjectStore store,
      Access access,
      AuditService audit,
      Clock clock) {
    this.db = db;
    this.subjects = subjects;
    this.store = store;
    this.access = access;
    this.audit = audit;
    this.clock = clock;
  }

  // ---------- список и тест ----------

  public List<Item> list(Actor actor, long subjectId) {
    subjects.visible(actor, subjectId);
    boolean edit = canEdit(actor, subjectId);
    List<Quiz> all =
        db.sql("SELECT * FROM quizzes WHERE subject_id = ? ORDER BY created_at DESC")
            .param(subjectId)
            .query((rs, i) -> quiz(rs))
            .list();
    List<Item> out = new ArrayList<>();
    for (Quiz q : all) {
      if (q.published() || edit) {
        out.add(item(actor, q, edit));
      }
    }
    return out;
  }

  public Detail get(Actor actor, long id) {
    Quiz q = visible(actor, id);
    boolean edit = canEdit(actor, q.subjectId());
    return new Detail(item(actor, q, edit), edit ? q.questions() : null, asks(q));
  }

  // ---------- ведущим: создать, изменить, удалить, результаты ----------

  @Transactional
  public Detail create(Actor actor, long subjectId, Input in) {
    subjects.visible(actor, subjectId);
    requireEdit(actor, subjectId);
    Input c = clean(in);
    long now = clock.millis();
    long id =
        db.sql(
                """
                INSERT INTO quizzes (subject_id, title, description, questions, time_limit,
                  attempts, show_answers, published, closes_at, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """)
            .params(
                subjectId,
                c.title(),
                c.description(),
                JSON.writeValueAsString(c.questions()),
                c.timeLimit(),
                c.attempts(),
                c.showAnswers() ? 1 : 0,
                c.published() ? 1 : 0,
                c.closesAt(),
                actor.id(),
                now,
                now)
            .query(Long.class)
            .single();
    audit.log(actor, null, "quiz.create", "quiz", id, Map.of("title", c.title()));
    return get(actor, id);
  }

  @Transactional
  public Detail update(Actor actor, long id, Input in) {
    Quiz q = visible(actor, id);
    requireEdit(actor, q.subjectId());
    Input c = clean(in);
    db.sql(
            """
            UPDATE quizzes SET title = ?, description = ?, questions = ?, time_limit = ?,
              attempts = ?, show_answers = ?, published = ?, closes_at = ?, updated_at = ?
            WHERE id = ?
            """)
        .params(
            c.title(),
            c.description(),
            JSON.writeValueAsString(c.questions()),
            c.timeLimit(),
            c.attempts(),
            c.showAnswers() ? 1 : 0,
            c.published() ? 1 : 0,
            c.closesAt(),
            clock.millis(),
            id)
        .update();
    audit.log(actor, null, "quiz.update", "quiz", id, Map.of("title", c.title()));
    return get(actor, id);
  }

  @Transactional
  public void delete(Actor actor, long id) {
    Quiz q = visible(actor, id);
    requireEdit(actor, q.subjectId());
    db.sql("DELETE FROM quizzes WHERE id = ?").param(id).update();
    audit.log(actor, null, "quiz.delete", "quiz", id, Map.of("title", q.title()));
  }

  /** Результаты: последняя законченная попытка каждого человека и начатые сейчас. */
  public List<Row> results(Actor actor, long id) {
    Quiz q = visible(actor, id);
    requireEdit(actor, q.subjectId());
    return db.sql(
            """
            SELECT a.id, a.user_id, u.display_name, a.started_at, a.finished_at, a.score,
              a.max_score
            FROM quiz_attempts a JOIN users u ON u.id = a.user_id
            WHERE a.quiz_id = ? ORDER BY u.display_name COLLATE NOCASE, a.started_at
            """)
        .param(id)
        .query(
            (rs, i) ->
                new Row(
                    rs.getLong(1),
                    rs.getLong(2),
                    rs.getString(3),
                    rs.getLong(4),
                    longOrNull(rs.getObject(5)),
                    doubleOrNull(rs.getObject(6)),
                    doubleOrNull(rs.getObject(7))))
        .list();
  }

  // ---------- пройти ----------

  /** Начать попытку (или вернуться в начатую). */
  @Transactional
  public Attempt start(Actor actor, long id) {
    Quiz q = visible(actor, id);
    long now = clock.millis();
    if (!q.published()) {
      throw ApiException.conflict("not_published", "Тест ещё не открыт");
    }
    Long open = openAttempt(actor.id(), q, now);
    if (open != null) {
      return attempt(q, open);
    }
    if (q.closesAt() != null && now > q.closesAt()) {
      throw ApiException.conflict("closed", "Тест уже закрыт");
    }
    int used = finishedCount(actor.id(), id);
    if (q.attempts() > 0 && used >= q.attempts()) {
      throw ApiException.conflict(
          "no_attempts", q.attempts() == 1 ? "Тест уже пройден" : "Попытки закончились");
    }
    long attemptId =
        db.sql(
                "INSERT INTO quiz_attempts (quiz_id, user_id, started_at) VALUES (?, ?, ?)"
                    + " RETURNING id")
            .params(id, actor.id(), now)
            .query(Long.class)
            .single();
    return attempt(q, attemptId);
  }

  /** Закончить попытку: сервер проверяет ответы и ставит баллы. */
  @Transactional
  public Result finish(Actor actor, long attemptId, List<Answer> answers) {
    Map<String, Object> a = attemptRow(attemptId);
    if (((Number) a.get("user_id")).longValue() != actor.id()) {
      throw ApiException.notFound();
    }
    if (a.get("finished_at") != null) {
      return result(actor, attemptId);
    }
    Quiz q = visible(actor, ((Number) a.get("quiz_id")).longValue());
    long now = clock.millis();
    long started = ((Number) a.get("started_at")).longValue();
    List<Answer> given = answers == null ? List.of() : answers;
    // Время вышло давно – ответы не принимаем: засчитывается то, что успели (ничего).
    if (q.timeLimit() != null && now > started + q.timeLimit() * 60_000L + GRACE_MS) {
      given = List.of();
    }
    double score = 0;
    double max = 0;
    for (int i = 0; i < q.questions().size(); i++) {
      Mark m = mark(q.questions().get(i), i < given.size() ? given.get(i) : null);
      score += m.points();
      max += m.max();
    }
    db.sql(
            "UPDATE quiz_attempts SET finished_at = ?, answers = ?, score = ?, max_score = ?"
                + " WHERE id = ?")
        .params(now, JSON.writeValueAsString(given), score, max, attemptId)
        .update();
    return result(actor, attemptId);
  }

  /** Итог попытки – тому, кто проходил, и ведущим. */
  public Result result(Actor actor, long attemptId) {
    Map<String, Object> a = attemptRow(attemptId);
    Quiz q = visible(actor, ((Number) a.get("quiz_id")).longValue());
    boolean own = ((Number) a.get("user_id")).longValue() == actor.id();
    boolean edit = canEdit(actor, q.subjectId());
    if (!own && !edit) {
      throw ApiException.notFound();
    }
    if (a.get("finished_at") == null) {
      throw ApiException.conflict("not_finished", "Попытка ещё не закончена");
    }
    List<Answer> given = JSON.readValue((String) a.get("answers"), ANSWERS);
    boolean reveal = edit || q.showAnswers();
    List<Mark> marks = new ArrayList<>();
    for (int i = 0; i < q.questions().size(); i++) {
      Mark m = mark(q.questions().get(i), i < given.size() ? given.get(i) : null);
      marks.add(reveal ? m : new Mark(m.right(), m.points(), m.max(), null, null));
    }
    return new Result(
        attemptId,
        q.id(),
        q.title(),
        ((Number) a.get("started_at")).longValue(),
        ((Number) a.get("finished_at")).longValue(),
        ((Number) a.get("score")).doubleValue(),
        ((Number) a.get("max_score")).doubleValue(),
        asks(q),
        given,
        marks);
  }

  // ---------- проверка ответа ----------

  static Mark mark(Question q, Answer a) {
    double max = points(q);
    boolean right =
        switch (q.kind()) {
          case "text" -> {
            String t = norm(a == null ? null : a.text());
            yield !t.isEmpty() && q.accepted().stream().map(QuizService::norm).anyMatch(t::equals);
          }
          default -> {
            Set<Integer> chosen =
                new LinkedHashSet<>(a == null || a.choices() == null ? List.of() : a.choices());
            yield chosen.equals(new LinkedHashSet<>(q.correct()));
          }
        };
    return new Mark(right, right ? max : 0, max, q.correct(), q.accepted());
  }

  static String norm(String s) {
    return s == null
        ? ""
        : s.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT).replace('ё', 'е');
  }

  private static int points(Question q) {
    return q.points() == null ? 1 : q.points();
  }

  // ---------- проверка ввода ----------

  static Input clean(Input in) {
    if (in == null) {
      throw ApiException.badRequest("Пустой тест");
    }
    String title = in.title() == null ? "" : in.title().strip();
    if (title.isEmpty() || title.length() > 200) {
      throw ApiException.invalid("title", "Название – от 1 до 200 символов");
    }
    String description = in.description() == null ? "" : in.description().strip();
    if (description.length() > 4000) {
      throw ApiException.invalid("description", "Описание – до 4000 символов");
    }
    List<Question> qs = in.questions() == null ? List.of() : in.questions();
    if (qs.isEmpty() || qs.size() > MAX_QUESTIONS) {
      throw ApiException.invalid("questions", "В тесте – от 1 до " + MAX_QUESTIONS + " вопросов");
    }
    List<Question> clean = new ArrayList<>();
    for (int i = 0; i < qs.size(); i++) {
      clean.add(question(qs.get(i), i + 1));
    }
    Integer limit = in.timeLimit();
    if (limit != null && (limit < 1 || limit > 600)) {
      throw ApiException.invalid("timeLimit", "Время – от 1 до 600 минут или без ограничения");
    }
    int attempts = in.attempts() == null ? 1 : in.attempts();
    if (attempts < 0 || attempts > 20) {
      throw ApiException.invalid("attempts", "Попыток – от 1 до 20 или без ограничения");
    }
    return new Input(
        title,
        description,
        clean,
        limit,
        attempts,
        in.showAnswers() == null || in.showAnswers(),
        Boolean.TRUE.equals(in.published()),
        in.closesAt());
  }

  private static Question question(Question q, int n) {
    String where = "Вопрос " + n + ": ";
    if (q == null) {
      throw ApiException.invalid("questions", where + "пустой");
    }
    String kind = q.kind() == null ? "single" : q.kind();
    if (!List.of("single", "multi", "text").contains(kind)) {
      throw ApiException.invalid("questions", where + "неизвестный вид");
    }
    String text = q.text() == null ? "" : q.text().strip();
    if (text.isEmpty() || text.length() > 2000) {
      throw ApiException.invalid("questions", where + "текст – от 1 до 2000 символов");
    }
    int points = q.points() == null ? 1 : q.points();
    if (points < 1 || points > 100) {
      throw ApiException.invalid("questions", where + "баллов – от 1 до 100");
    }
    if (kind.equals("text")) {
      List<String> accepted =
          (q.accepted() == null ? List.<String>of() : q.accepted())
              .stream()
                  .map(s -> s == null ? "" : s.strip())
                  .filter(s -> !s.isEmpty())
                  .distinct()
                  .toList();
      if (accepted.isEmpty()
          || accepted.size() > 10
          || accepted.stream().anyMatch(s -> s.length() > 200)) {
        throw ApiException.invalid("questions", where + "укажите от 1 до 10 верных ответов");
      }
      return new Question(kind, text, List.of(), List.of(), accepted, points);
    }
    List<String> options =
        (q.options() == null ? List.<String>of() : q.options())
            .stream().map(s -> s == null ? "" : s.strip()).toList();
    if (options.size() < 2
        || options.size() > MAX_OPTIONS
        || options.stream().anyMatch(s -> s.isEmpty() || s.length() > 500)) {
      throw ApiException.invalid(
          "questions", where + "от 2 до " + MAX_OPTIONS + " вариантов, не пустых");
    }
    List<Integer> correct =
        (q.correct() == null ? List.<Integer>of() : q.correct())
            .stream().distinct().sorted().toList();
    if (correct.isEmpty()
        || correct.stream().anyMatch(c -> c == null || c < 0 || c >= options.size())) {
      throw ApiException.invalid("questions", where + "отметьте верный вариант");
    }
    if (kind.equals("single") && correct.size() != 1) {
      throw ApiException.invalid("questions", where + "верный вариант – ровно один");
    }
    return new Question(kind, text, options, correct, List.of(), points);
  }

  // ---------- служебное ----------

  private Quiz visible(Actor actor, long id) {
    Quiz q =
        db.sql("SELECT * FROM quizzes WHERE id = ?")
            .param(id)
            .query((rs, i) -> quiz(rs))
            .optional()
            .orElseThrow(ApiException::notFound);
    subjects.visible(actor, q.subjectId());
    if (!q.published() && !canEdit(actor, q.subjectId())) {
      throw ApiException.notFound();
    }
    return q;
  }

  private boolean canEdit(Actor actor, long subjectId) {
    return access.can(actor, Permission.MANAGE_SUBJECTS, store.groupIds(subjectId));
  }

  private void requireEdit(Actor actor, long subjectId) {
    if (!canEdit(actor, subjectId)) {
      throw ApiException.forbidden("Тесты ведут староста и заместители");
    }
  }

  private Quiz quiz(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Quiz(
        rs.getLong("id"),
        rs.getLong("subject_id"),
        rs.getString("title"),
        rs.getString("description"),
        JSON.readValue(rs.getString("questions"), QUESTIONS),
        intOrNull(rs.getObject("time_limit")),
        rs.getInt("attempts"),
        rs.getInt("show_answers") != 0,
        rs.getInt("published") != 0,
        longOrNull(rs.getObject("closes_at")),
        rs.getLong("created_at"));
  }

  private Item item(Actor actor, Quiz q, boolean edit) {
    long now = clock.millis();
    Map<String, Object> my =
        db.sql(
                """
                SELECT COUNT(finished_at) AS used, MAX(score) AS best, MAX(max_score) AS mx,
                  (SELECT id FROM quiz_attempts WHERE quiz_id = ?1 AND user_id = ?2
                     AND finished_at IS NOT NULL ORDER BY finished_at DESC LIMIT 1) AS last
                FROM quiz_attempts WHERE quiz_id = ?1 AND user_id = ?2
                """)
            .params(q.id(), actor.id())
            .query()
            .singleRow();
    Stats stats = null;
    if (edit) {
      Map<String, Object> s =
          db.sql(
                  """
                  SELECT COUNT(DISTINCT user_id) AS people,
                    AVG(CASE WHEN max_score > 0 THEN score * 100.0 / max_score END) AS avg
                  FROM quiz_attempts WHERE quiz_id = ? AND finished_at IS NOT NULL
                  """)
              .param(q.id())
              .query()
              .singleRow();
      stats =
          new Stats(
              ((Number) s.get("people")).intValue(),
              s.get("avg") == null ? null : ((Number) s.get("avg")).doubleValue());
    }
    double points = q.questions().stream().mapToInt(QuizService::points).sum();
    return new Item(
        q.id(),
        q.subjectId(),
        q.title(),
        q.description(),
        q.questions().size(),
        points,
        q.timeLimit(),
        q.attempts(),
        q.showAnswers(),
        q.published(),
        q.closesAt(),
        q.createdAt(),
        new Mine(
            ((Number) my.get("used")).intValue(),
            my.get("best") == null ? null : ((Number) my.get("best")).doubleValue(),
            my.get("mx") == null ? null : ((Number) my.get("mx")).doubleValue(),
            openAttempt(actor.id(), q, now),
            longOrNull(my.get("last"))),
        stats,
        new Can(edit));
  }

  /** Начатая и ещё не истёкшая попытка; истёкшие закрываются с тем, что успели (ничего). */
  private Long openAttempt(long userId, Quiz q, long now) {
    List<Map<String, Object>> open =
        db.sql(
                "SELECT id, started_at FROM quiz_attempts"
                    + " WHERE quiz_id = ? AND user_id = ? AND finished_at IS NULL")
            .params(q.id(), userId)
            .query()
            .listOfRows();
    Long keep = null;
    for (Map<String, Object> r : open) {
      long started = ((Number) r.get("started_at")).longValue();
      long id = ((Number) r.get("id")).longValue();
      if (q.timeLimit() != null && now > started + q.timeLimit() * 60_000L + GRACE_MS) {
        double max = q.questions().stream().mapToInt(QuizService::points).sum();
        db.sql("UPDATE quiz_attempts SET finished_at = ?, score = 0, max_score = ? WHERE id = ?")
            .params(started + q.timeLimit() * 60_000L, max, id)
            .update();
      } else if (keep == null) {
        keep = id;
      }
    }
    return keep;
  }

  private int finishedCount(long userId, long quizId) {
    return db.sql(
            "SELECT COUNT(*) FROM quiz_attempts WHERE quiz_id = ? AND user_id = ?"
                + " AND finished_at IS NOT NULL")
        .params(quizId, userId)
        .query(Integer.class)
        .single();
  }

  private Attempt attempt(Quiz q, long attemptId) {
    long started =
        db.sql("SELECT started_at FROM quiz_attempts WHERE id = ?")
            .param(attemptId)
            .query(Long.class)
            .single();
    return new Attempt(
        attemptId,
        q.id(),
        q.title(),
        started,
        q.timeLimit() == null ? null : started + q.timeLimit() * 60_000L,
        asks(q));
  }

  private Map<String, Object> attemptRow(long id) {
    return db
        .sql("SELECT * FROM quiz_attempts WHERE id = ?")
        .param(id)
        .query()
        .listOfRows()
        .stream()
        .findFirst()
        .orElseThrow(ApiException::notFound);
  }

  // SQLite отдаёт NULL как null, а getObject(…, Long.class) на нём падает.
  private static Long longOrNull(Object o) {
    return o == null ? null : ((Number) o).longValue();
  }

  private static Integer intOrNull(Object o) {
    return o == null ? null : ((Number) o).intValue();
  }

  private static Double doubleOrNull(Object o) {
    return o == null ? null : ((Number) o).doubleValue();
  }

  private static List<Ask> asks(Quiz q) {
    List<Ask> out = new ArrayList<>();
    for (Question x : q.questions()) {
      out.add(new Ask(x.kind(), x.text(), x.options(), points(x)));
    }
    return out;
  }
}
