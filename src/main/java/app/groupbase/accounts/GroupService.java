package app.groupbase.accounts;

import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Authz;
import app.groupbase.auth.Permission;
import app.groupbase.config.GroupbaseProperties;
import app.groupbase.store.Group;
import app.groupbase.store.GroupStore;
import app.groupbase.store.Member;
import app.groupbase.web.ApiException;
import java.time.Clock;
import java.time.ZoneId;
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
  private final ZoneId zone;

  public GroupService(
      GroupStore groups,
      Authz authz,
      InstanceSettings settings,
      AuditService audit,
      Clock clock,
      GroupbaseProperties props) {
    this.groups = groups;
    this.authz = authz;
    this.settings = settings;
    this.audit = audit;
    this.clock = clock;
    this.zone = props.timezone();
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
          "single_mode",
          "Сайт в режиме одной группы. Включите режим нескольких групп в «Настройки → Сайт»");
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

  /**
   * Даты сессии: первый и последний день включительно. Оба null — сессии нет. Дни приводятся к
   * полуночи по часовому поясу сайта.
   */
  @Transactional
  public Group setSession(Actor actor, long groupId, Long from, Long to) {
    groups.find(groupId).orElseThrow(ApiException::notFound);
    if (from == null && to == null) {
      groups.setSession(groupId, null, null);
    } else {
      if (from == null || to == null) {
        throw ApiException.invalid("to", "Укажите и начало, и конец сессии");
      }
      long f = midnight(from);
      long t = midnight(to);
      if (t < f) {
        throw ApiException.invalid("to", "Конец сессии раньше начала");
      }
      if (t - f > SESSION_MAX_DAYS * DAY) {
        throw ApiException.invalid("to", "Сессия — не дольше двух месяцев");
      }
      long now = clock.millis();
      if (f < now - 365 * DAY || f > now + 365 * DAY) {
        throw ApiException.invalid("from", "Сессия — в пределах года от сегодняшнего дня");
      }
      groups.setSession(groupId, f, t);
    }
    audit.log(actor, groupId, "group.session", "group", groupId);
    return groups.find(groupId).orElseThrow();
  }

  private static final long DAY = 24L * 60 * 60 * 1000;
  static final int SESSION_MAX_DAYS = 62;

  private long midnight(long ms) {
    return java.time.Instant.ofEpochMilli(ms)
        .atZone(zone)
        .toLocalDate()
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli();
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
