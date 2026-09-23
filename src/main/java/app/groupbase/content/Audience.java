package app.groupbase.content;

import app.groupbase.auth.Actor;
import app.groupbase.auth.Permission;
import app.groupbase.web.ApiException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Кому адресована публикация. С предметом: подмножество групп предмета (пусто — все группы
 * предмета, где у автора есть право). Без предмета: явно перечисленные группы.
 */
@Component
public class Audience {

  private final SubjectStore subjects;
  private final Access access;

  public Audience(SubjectStore subjects, Access access) {
    this.subjects = subjects;
    this.access = access;
  }

  public Set<Long> resolve(Actor actor, Long subjectId, List<Long> requested, Permission p) {
    Set<Long> groups = new LinkedHashSet<>(requested == null ? List.of() : requested);
    if (subjectId != null) {
      subjects.find(subjectId).orElseThrow(ApiException::notFound);
      List<Long> subjectGroups = subjects.groupIds(subjectId);
      access.requireSee(actor, subjectGroups);
      if (groups.isEmpty()) {
        groups.addAll(access.groupsWith(actor, p, subjectGroups));
        if (groups.isEmpty()) {
          throw ApiException.forbidden();
        }
      } else if (!subjectGroups.containsAll(groups)) {
        throw ApiException.invalid("groupIds", "Группы должны быть связаны с предметом");
      }
    }
    if (groups.isEmpty()) {
      throw ApiException.invalid("groupIds", "Выберите хотя бы одну группу");
    }
    access.requireAll(actor, p, groups);
    return groups;
  }
}
