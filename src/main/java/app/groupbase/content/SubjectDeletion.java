package app.groupbase.content;

import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Permission;
import app.groupbase.avatars.AvatarService;
import app.groupbase.files.FileStore;
import app.groupbase.web.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Удалить предмет совсем (0.7, просьба владельца; раньше — только в архив). Вместе с ним уходят его
 * задания, материалы, папки и комментарии к ним, файлы на диске, картинка и обложка; пары
 * расписания и новости остаются — без предмета. Общий предмет удаляет только тот, кто ведёт
 * предметы во всех его группах.
 */
@Service
public class SubjectDeletion {

  private final JdbcClient db;
  private final SubjectService subjects;
  private final SubjectStore store;
  private final Access access;
  private final FileStore files;
  private final AvatarService avatars;
  private final AuditService audit;

  public SubjectDeletion(
      JdbcClient db,
      SubjectService subjects,
      SubjectStore store,
      Access access,
      FileStore files,
      AvatarService avatars,
      AuditService audit) {
    this.db = db;
    this.subjects = subjects;
    this.store = store;
    this.access = access;
    this.files = files;
    this.avatars = avatars;
    this.audit = audit;
  }

  @Transactional
  public void delete(Actor actor, long id) {
    SubjectStore.Row s = subjects.visible(actor, id);
    List<Long> groups = store.groupIds(id);
    for (long g : groups) {
      if (!access.can(actor, Permission.MANAGE_SUBJECTS, List.of(g))) {
        throw ApiException.forbidden(
            "Предмет общий с другой группой – сначала отвяжите его или попросите их старосту");
      }
    }
    // Файлы заданий и материалов: строки в базе уйдут каскадом, а сами файлы на диске — нет.
    List<Long> fileIds =
        db.sql(
                """
                SELECT file_id FROM materials WHERE subject_id = :s AND file_id IS NOT NULL
                UNION
                SELECT a.file_id FROM homework_attachments a
                  JOIN homework h ON h.id = a.homework_id WHERE h.subject_id = :s
                """)
            .param("s", id)
            .query(Long.class)
            .list();
    db.sql(
            """
            DELETE FROM comments WHERE
              (target_type = 'homework' AND target_id IN (SELECT id FROM homework WHERE subject_id = :s))
              OR (target_type = 'material'
                  AND target_id IN (SELECT id FROM materials WHERE subject_id = :s))
            """)
        .param("s", id)
        .update();
    store.delete(id);
    for (long f : fileIds) {
      files.find(f).ifPresent(files::delete);
    }
    avatars.delete(s.avatar());
    avatars.delete(s.cover());
    audit.log(
        actor,
        groups.isEmpty() ? null : groups.getFirst(),
        "subject.delete",
        "subject",
        id,
        Map.of("name", s.name()));
  }
}
