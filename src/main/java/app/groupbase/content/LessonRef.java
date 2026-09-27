package app.groupbase.content;

import app.groupbase.auth.Actor;
import app.groupbase.store.Rows;
import app.groupbase.web.ApiException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Пара из расписания, к которой относится задание («сдать к этой паре») или материал («слайды этой
 * лекции»). Сами пары — в пакете schedule.
 */
public record LessonRef(long id, long startsAt, long endsAt, String kind, String place) {

  private record Owner(long groupId, Long subjectId) {}

  /**
   * Пару можно привязать к заданию или материалу предмета, если она того же предмета и её группа
   * видна человеку. Иначе — ошибка в поле lessonId.
   */
  static void check(JdbcClient db, Access access, Actor actor, long lessonId, long subjectId) {
    Owner o =
        db.sql("SELECT group_id, subject_id FROM lessons WHERE id = ?")
            .param(lessonId)
            .query((rs, i) -> new Owner(rs.getLong(1), Rows.longOrNull(rs, "subject_id")))
            .optional()
            .orElseThrow(() -> ApiException.invalid("lessonId", "Такой пары нет в расписании"));
    if (!access.canSee(actor, List.of(o.groupId()))
        || o.subjectId() == null
        || o.subjectId() != subjectId) {
      throw ApiException.invalid("lessonId", "Пара другого предмета");
    }
  }
}
