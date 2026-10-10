package app.groupbase.content;

import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Authz;
import app.groupbase.auth.Permission;
import app.groupbase.config.GroupbaseProperties;
import app.groupbase.web.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Архивы прошлых семестров (0.9.7): предметы закончившегося семестра уходят в архив вместе, под
 * одним названием. Задания, материалы, тесты и расписание остаются у предметов – архив только
 * убирает их из текущих списков и собирает на странице «Предметы» по семестрам. Расформировать
 * архив – вернуть его предметы в текущие.
 */
@Service
public class SemesterService {

  public record SemesterView(long id, String name, long createdAt, int subjects) {}

  /**
   * @param manual пустой архив, который заполнят вручную (1.0.2): предметы не нужны
   */
  public record Input(String name, List<Long> subjects, Boolean manual) {
    public Input(String name, List<Long> subjects) {
      this(name, subjects, null);
    }
  }

  static final int NAME_MAX = 80;

  private final JdbcClient db;
  private final SubjectStore subjects;
  private final Authz authz;
  private final Access access;
  private final AuditService audit;
  private final Clock clock;
  private final ZoneId zone;
  private final SubjectService subjectService;

  public SemesterService(
      JdbcClient db,
      SubjectStore subjects,
      Authz authz,
      Access access,
      AuditService audit,
      Clock clock,
      GroupbaseProperties props,
      SubjectService subjectService) {
    this.db = db;
    this.subjects = subjects;
    this.authz = authz;
    this.access = access;
    this.audit = audit;
    this.clock = clock;
    this.zone = props.timezone();
    this.subjectService = subjectService;
  }

  /**
   * Название по умолчанию: февраль–июль – весенний семестр, остальное – осенний того учебного года.
   */
  static String defaultName(LocalDate day) {
    int m = day.getMonthValue();
    if (m >= 2 && m <= 7) {
      return "Весенний семестр " + day.getYear();
    }
    return "Осенний семестр " + (m == 1 ? day.getYear() - 1 : day.getYear());
  }

  public String suggestedName() {
    return defaultName(LocalDate.now(clock.withZone(zone)));
  }

  public List<SemesterView> list(Actor actor, long groupId) {
    access.requireSee(actor, List.of(groupId));
    return db.sql(
            """
            SELECT s.id, s.name, s.created_at,
              (SELECT COUNT(*) FROM subjects x WHERE x.semester_id = s.id) AS n
            FROM semesters s WHERE s.group_id = ? ORDER BY s.created_at DESC, s.id DESC
            """)
        .param(groupId)
        .query(
            (rs, i) ->
                new SemesterView(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getLong("created_at"),
                    rs.getInt("n")))
        .list();
  }

  /**
   * Новый архив: предметы группы уходят в него (уже архивные – тоже, без даты архивации заново).
   */
  @Transactional
  public SemesterView create(Actor actor, long groupId, Input in) {
    authz.require(actor, Permission.MANAGE_SUBJECTS, groupId);
    String name = clean(in.name());
    Set<Long> ids = new LinkedHashSet<>(in.subjects() == null ? List.of() : in.subjects());
    if (ids.isEmpty() && !Boolean.TRUE.equals(in.manual())) {
      throw ApiException.badRequest("Выберите предметы, которые уходят в архив");
    }
    for (long id : ids) {
      List<Long> groups = subjects.groupIds(id);
      if (!groups.contains(groupId)) {
        throw ApiException.badRequest("Предмет не из этой группы");
      }
      if (!access.can(actor, Permission.MANAGE_SUBJECTS, groups)) {
        throw ApiException.forbidden();
      }
    }
    long now = clock.millis();
    long semester =
        db.sql(
                "INSERT INTO semesters (group_id, name, created_by, created_at)"
                    + " VALUES (?, ?, ?, ?) RETURNING id")
            .params(groupId, name, actor.id(), now)
            .query(Long.class)
            .single();
    if (!ids.isEmpty()) {
      db.sql(
              "UPDATE subjects SET archived_at = COALESCE(archived_at, :now), semester_id = :s"
                  + " WHERE id IN (:ids)")
          .param("now", now)
          .param("s", semester)
          .param("ids", ids)
          .update();
    }
    audit.log(actor, groupId, "semester.create", "semester", semester, Map.of("name", name));
    return new SemesterView(semester, name, now, ids.size());
  }

  /**
   * Предмет прямо в архив (1.0.2): прошлый семестр заполняют задним числом – файлы, конспекты и
   * задания загружают на странице предмета, как обычно (уведомлений группе нет – см. Notifier).
   */
  @Transactional
  public SubjectService.SubjectView addSubject(Actor actor, long id, SubjectService.Input in) {
    long group = requireManage(actor, id);
    SubjectService.SubjectView created = subjectService.create(actor, group, in);
    db.sql("UPDATE subjects SET archived_at = ?, semester_id = ? WHERE id = ?")
        .params(clock.millis(), id, created.id())
        .update();
    audit.log(actor, group, "semester.subject", "semester", id, Map.of("name", created.name()));
    return subjectService.get(actor, created.id());
  }

  @Transactional
  public void rename(Actor actor, long id, String name) {
    long group = requireManage(actor, id);
    String clean = clean(name);
    db.sql("UPDATE semesters SET name = ? WHERE id = ?").params(clean, id).update();
    audit.log(actor, group, "semester.rename", "semester", id, Map.of("name", clean));
  }

  /** Расформировать архив: его предметы возвращаются в текущие. */
  @Transactional
  public void restore(Actor actor, long id) {
    long group = requireManage(actor, id);
    String name =
        db.sql("SELECT name FROM semesters WHERE id = ?").param(id).query(String.class).single();
    db.sql("UPDATE subjects SET archived_at = NULL, semester_id = NULL WHERE semester_id = ?")
        .param(id)
        .update();
    db.sql("DELETE FROM semesters WHERE id = ?").param(id).update();
    audit.log(actor, group, "semester.restore", "semester", id, Map.of("name", name));
  }

  /** Группа архива, если человек управляет в ней предметами; чужой архив – 404. */
  private long requireManage(Actor actor, long id) {
    Long group =
        db.sql("SELECT group_id FROM semesters WHERE id = ?")
            .param(id)
            .query(Long.class)
            .optional()
            .orElseThrow(ApiException::notFound);
    access.requireSee(actor, List.of(group));
    authz.require(actor, Permission.MANAGE_SUBJECTS, group);
    return group;
  }

  private String clean(String name) {
    String s = name == null ? "" : name.strip().replaceAll("\\s+", " ");
    if (s.isEmpty()) {
      return suggestedName();
    }
    if (s.length() > NAME_MAX) {
      throw ApiException.badRequest("Название архива – не длиннее " + NAME_MAX + " знаков");
    }
    return s;
  }
}
