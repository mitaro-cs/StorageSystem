package app.groupbase.content;

import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Permission;
import app.groupbase.store.GroupStore;
import app.groupbase.web.ApiException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Предметы. Предмет принадлежит одной или нескольким группам. Связать с чужой группой можно сразу,
 * если право SHARE_SUBJECTS есть и во второй группе (admin, староста обеих групп); иначе создаётся
 * запрос, который принимает староста второй группы.
 */
@Service
public class SubjectService {

  /**
   * @param chatUrl чат предмета в Telegram: null — не менять, пустая строка — убрать
   * @param icon иконка из встроенного набора: null — не менять, пустая строка — подобрать по
   *     названию
   */
  public record Input(String name, String teacher, String color, String chatUrl, String icon) {
    public Input(String name, String teacher, String color, String chatUrl) {
      this(name, teacher, color, chatUrl, null);
    }
  }

  public record SubjectView(
      long id,
      String name,
      String teacher,
      String color,
      String avatar,
      String icon,
      String cover,
      String chatUrl,
      boolean archived,
      boolean pinned,
      List<SubjectStore.GroupRef> groups,
      Can can) {}

  public record Can(boolean edit, boolean share) {}

  public record LinkResult(String status, Long requestId) {}

  private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

  /** Ключ иконки из набора интерфейса: латиница, цифры и дефис. */
  static final Pattern ICON = Pattern.compile("[a-z0-9-]{1,32}");

  private final SubjectStore subjects;
  private final GroupStore groups;
  private final Access access;
  private final AuditService audit;
  private final Clock clock;

  public SubjectService(
      SubjectStore subjects, GroupStore groups, Access access, AuditService audit, Clock clock) {
    this.subjects = subjects;
    this.groups = groups;
    this.access = access;
    this.audit = audit;
    this.clock = clock;
  }

  public List<SubjectView> list(Actor actor, Long onlyGroup) {
    List<SubjectStore.Row> rows = subjects.visible(access.scope(actor, onlyGroup));
    var groupMap = subjects.groupsOf(rows.stream().map(SubjectStore.Row::id).toList());
    Set<Long> pins = subjects.pinned(actor.id());
    return rows.stream()
        .map(r -> view(actor, r, groupMap.getOrDefault(r.id(), List.of()), pins))
        .toList();
  }

  public SubjectView get(Actor actor, long id) {
    SubjectStore.Row r = visible(actor, id);
    return view(
        actor,
        r,
        subjects.groupsOf(List.of(id)).getOrDefault(id, List.of()),
        subjects.pinned(actor.id()));
  }

  /** Предмет, видимый пользователю, иначе 404 (не раскрываем существование). */
  public SubjectStore.Row visible(Actor actor, long id) {
    SubjectStore.Row r = subjects.find(id).orElseThrow(ApiException::notFound);
    access.requireSee(actor, subjects.groupIds(id));
    return r;
  }

  private SubjectView view(
      Actor actor, SubjectStore.Row r, List<SubjectStore.GroupRef> gs, Set<Long> pins) {
    List<Long> ids = gs.stream().map(SubjectStore.GroupRef::id).toList();
    return new SubjectView(
        r.id(),
        r.name(),
        r.teacher(),
        r.color(),
        r.avatar(),
        r.icon(),
        r.cover(),
        r.chatUrl(),
        r.archivedAt() != null,
        pins.contains(r.id()),
        gs,
        new Can(
            access.can(actor, Permission.MANAGE_SUBJECTS, ids),
            access.can(actor, Permission.SHARE_SUBJECTS, ids)));
  }

  @Transactional
  public SubjectView create(Actor actor, long groupId, Input in) {
    groups.find(groupId).orElseThrow(ApiException::notFound);
    Input c = clean(in);
    long now = clock.millis();
    long id = subjects.insert(c.name(), c.teacher(), c.color(), actor.id(), now);
    if (c.chatUrl() != null && !c.chatUrl().isEmpty()) {
      subjects.setChat(id, c.chatUrl());
    }
    if (c.icon() != null && !c.icon().isEmpty()) {
      subjects.setIcon(id, c.icon());
    }
    subjects.link(id, groupId, now);
    audit.log(actor, groupId, "subject.create", "subject", id, Map.of("name", c.name()));
    return get(actor, id);
  }

  @Transactional
  public SubjectView update(Actor actor, long id, Input in) {
    requireManage(actor, id);
    Input c = clean(in);
    subjects.update(id, c.name(), c.teacher(), c.color());
    if (c.chatUrl() != null) {
      subjects.setChat(id, c.chatUrl().isEmpty() ? null : c.chatUrl());
    }
    if (c.icon() != null) {
      subjects.setIcon(id, c.icon().isEmpty() ? null : c.icon());
    }
    return get(actor, id);
  }

  @Transactional
  public void setArchived(Actor actor, long id, boolean archived) {
    requireManage(actor, id);
    subjects.setArchived(id, archived ? clock.millis() : null);
    audit.log(actor, null, archived ? "subject.archive" : "subject.unarchive", "subject", id);
  }

  public void setPinned(Actor actor, long id, boolean pinned) {
    visible(actor, id);
    if (pinned) {
      subjects.pin(actor.id(), id, clock.millis());
    } else {
      subjects.unpin(actor.id(), id);
    }
  }

  /** Связать предмет с группой: сразу или через запрос к старосте второй группы. */
  @Transactional
  public LinkResult link(Actor actor, long id, long targetGroup) {
    visible(actor, id);
    List<Long> current = subjects.groupIds(id);
    groups.find(targetGroup).orElseThrow(ApiException::notFound);
    if (current.contains(targetGroup)) {
      throw ApiException.conflict("already_linked", "Предмет уже связан с этой группой");
    }
    Set<Long> from = access.groupsWith(actor, Permission.SHARE_SUBJECTS, current);
    if (from.isEmpty()) {
      throw ApiException.forbidden();
    }
    long now = clock.millis();
    if (access.can(actor, Permission.SHARE_SUBJECTS, List.of(targetGroup))) {
      subjects.link(id, targetGroup, now);
      audit.log(actor, targetGroup, "subject.link", "subject", id);
      return new LinkResult("linked", null);
    }
    if (subjects.pendingExists(id, targetGroup)) {
      throw ApiException.conflict("request_pending", "Запрос уже отправлен и ждёт ответа");
    }
    long req = subjects.insertRequest(id, from.iterator().next(), targetGroup, actor.id(), now);
    audit.log(actor, from.iterator().next(), "subject.link_request", "subject", id);
    return new LinkResult("requested", req);
  }

  /** Ожидающие запросы, которые пользователь может принять или отозвать. */
  public List<SubjectStore.LinkRequest> requests(Actor actor) {
    Set<Long> mine =
        access.groupsWith(actor, Permission.SHARE_SUBJECTS, access.visibleGroups(actor));
    return subjects.pendingFor(mine);
  }

  @Transactional
  public void decide(Actor actor, long requestId, boolean accept) {
    SubjectStore.LinkRequest r = subjects.request(requestId).orElseThrow(ApiException::notFound);
    if (!"pending".equals(r.status())) {
      throw ApiException.conflict("decided", "Запрос уже обработан");
    }
    boolean canDecide = access.can(actor, Permission.SHARE_SUBJECTS, List.of(r.toGroupId()));
    boolean canCancel = access.can(actor, Permission.SHARE_SUBJECTS, List.of(r.fromGroupId()));
    long now = clock.millis();
    if (accept) {
      if (!canDecide) {
        throw ApiException.forbidden("Принять запрос может староста группы " + r.toGroupName());
      }
      subjects.decide(requestId, "accepted", actor.id(), now);
      subjects.link(r.subjectId(), r.toGroupId(), now);
      audit.log(actor, r.toGroupId(), "subject.link_accept", "subject", r.subjectId());
    } else {
      if (!canDecide && !canCancel) {
        throw ApiException.forbidden();
      }
      subjects.decide(requestId, canDecide ? "rejected" : "cancelled", actor.id(), now);
      audit.log(actor, r.toGroupId(), "subject.link_reject", "subject", r.subjectId());
    }
  }

  /** Отвязать группу от общего предмета. Последнюю группу отвязать нельзя — только архивировать. */
  @Transactional
  public void unlink(Actor actor, long id, long groupId) {
    visible(actor, id);
    List<Long> current = subjects.groupIds(id);
    if (!current.contains(groupId)) {
      throw ApiException.notFound();
    }
    if (current.size() == 1) {
      throw ApiException.conflict(
          "last_group", "Это единственная группа предмета. Предмет можно архивировать");
    }
    if (!access.can(actor, Permission.SHARE_SUBJECTS, List.of(groupId))
        && !access.can(actor, Permission.MANAGE_SUBJECTS, List.of(groupId))) {
      throw ApiException.forbidden();
    }
    subjects.unlink(id, groupId);
    audit.log(actor, groupId, "subject.unlink", "subject", id);
  }

  private void requireManage(Actor actor, long id) {
    visible(actor, id);
    if (!access.can(actor, Permission.MANAGE_SUBJECTS, subjects.groupIds(id))) {
      throw ApiException.forbidden();
    }
  }

  private static Input clean(Input in) {
    String name = in.name() == null ? "" : in.name().strip();
    if (name.isEmpty() || name.length() > 80) {
      throw ApiException.invalid("name", "Название предмета: от 1 до 80 символов");
    }
    String teacher = in.teacher() == null ? "" : in.teacher().strip();
    if (teacher.length() > 80) {
      throw ApiException.invalid("teacher", "Имя преподавателя — до 80 символов");
    }
    String color = in.color() == null || in.color().isBlank() ? "#6b7280" : in.color().strip();
    if (!COLOR.matcher(color).matches()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "invalid", "Цвет в формате #rrggbb");
    }
    String chat = in.chatUrl() == null ? null : Telegram.normalize(in.chatUrl(), "chatUrl");
    return new Input(
        name, teacher, color.toLowerCase(java.util.Locale.ROOT), chat, icon(in.icon()));
  }

  /** null — не менять, пустая строка — убрать, иначе ключ из набора. */
  static String icon(String raw) {
    if (raw == null) {
      return null;
    }
    String icon = raw.strip().toLowerCase(java.util.Locale.ROOT);
    if (!icon.isEmpty() && !ICON.matcher(icon).matches()) {
      throw ApiException.invalid("icon", "Неизвестная иконка");
    }
    return icon;
  }
}
