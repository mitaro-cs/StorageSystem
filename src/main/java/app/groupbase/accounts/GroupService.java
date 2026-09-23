package app.groupbase.accounts;

import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Authz;
import app.groupbase.auth.Permission;
import app.groupbase.store.Group;
import app.groupbase.store.GroupStore;
import app.groupbase.store.Member;
import app.groupbase.web.ApiException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Группы инстанса. Создание и настройки — только admin (MANAGE_INSTANCE). */
@Service
public class GroupService {

  public record GroupInput(String name, String university, Integer course) {}

  private final GroupStore groups;
  private final Authz authz;
  private final InstanceSettings settings;
  private final AuditService audit;
  private final Clock clock;

  public GroupService(
      GroupStore groups, Authz authz, InstanceSettings settings, AuditService audit, Clock clock) {
    this.groups = groups;
    this.authz = authz;
    this.settings = settings;
    this.audit = audit;
    this.clock = clock;
  }

  /** Участники группы; логины видят только те, у кого есть право VIEW_USERNAMES. */
  public List<Member> members(Actor actor, long groupId) {
    List<Member> list = groups.members(groupId);
    if (authz.can(actor, Permission.VIEW_USERNAMES, groupId)) {
      return list;
    }
    return list.stream().map(Member::withoutUsername).toList();
  }

  public List<Group> visible(Actor actor) {
    return groups.listByIds(authz.visibleGroupIds(actor));
  }

  @Transactional
  public Group create(Actor actor, GroupInput in) {
    if (settings.mode() == InstanceSettings.Mode.SINGLE && groups.count() > 0) {
      throw ApiException.conflict(
          "single_mode", "Инстанс в режиме одной группы. Включите режим нескольких групп");
    }
    Group g = createSystem(in);
    audit.log(actor, g.id(), "group.create", "group", g.id(), Map.of("name", g.name()));
    return g;
  }

  /** Без проверок: мастер первого запуска и CLI. */
  @Transactional
  public Group createSystem(GroupInput in) {
    Clean c = clean(in);
    String base = Names.slug(c.name);
    String slug = base;
    for (int n = 2; groups.slugTaken(slug); n++) {
      slug = base + "-" + n;
    }
    long id = groups.insert(slug, c.name, c.university, c.course, clock.millis());
    return groups.find(id).orElseThrow();
  }

  @Transactional
  public Group update(Actor actor, long groupId, GroupInput in) {
    groups.find(groupId).orElseThrow(ApiException::notFound);
    Clean c = clean(in);
    groups.update(groupId, c.name, c.university, c.course);
    audit.log(actor, groupId, "group.update", "group", groupId);
    return groups.find(groupId).orElseThrow();
  }

  @Transactional
  public void setArchived(Actor actor, long groupId, boolean archived) {
    groups.find(groupId).orElseThrow(ApiException::notFound);
    groups.setArchived(groupId, archived ? clock.millis() : null);
    audit.log(actor, groupId, archived ? "group.archive" : "group.unarchive", "group", groupId);
  }

  private record Clean(String name, String university, Integer course) {}

  private static Clean clean(GroupInput in) {
    String name = in.name() == null ? "" : in.name().strip();
    if (name.isEmpty() || name.length() > 40) {
      throw ApiException.invalid("name", "Название группы: от 1 до 40 символов");
    }
    String uni = in.university() == null ? "" : in.university().strip();
    if (uni.length() > 80) {
      throw ApiException.invalid("university", "Название вуза — до 80 символов");
    }
    if (in.course() != null && (in.course() < 1 || in.course() > 6)) {
      throw ApiException.invalid("course", "Курс: от 1 до 6");
    }
    return new Clean(name, uni, in.course());
  }
}
