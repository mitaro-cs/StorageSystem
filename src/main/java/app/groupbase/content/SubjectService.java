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
  /**
   * @param teachers преподаватели по видам пар (lecture, practice, seminar, lab): null — не менять
   */
  public record Input(
      String name,
      String teacher,
      String color,
      String chatUrl,
      String icon,
      Map<String, String> teachers) {
    public Input(String name, String teacher, String color, String chatUrl) {
      this(name, teacher, color, chatUrl, null, null);
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
      // Свой преподаватель у лекций, практики, семинаров, лабораторных (0.9.6).
      Map<String, String> teachers,
      boolean archived,
      boolean pinned,
      // Предмет этого человека; false — скрыл у себя как предмет другой подгруппы.
      boolean mine,
      List<SubjectStore.GroupRef> groups,
      Can can,
      // Пар в расписании (в группах, которые видит человек): есть — у предмета вкладка «Пары».
      int lessons) {}

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
    List<Long> ids = rows.stream().map(SubjectStore.Row::id).toList();
    var groupMap = subjects.groupsOf(ids);
    Set<Long> pins = subjects.pinned(actor.id());
    Set<Long> hidden = subjects.hidden(actor.id());
    Map<Long, Integer> lessons = subjects.lessonCounts(ids, access.visibleGroups(actor));
    return rows.stream()
        .map(
            r ->
                view(
                    actor,
                    r,
                    groupMap.getOrDefault(r.id(), List.of()),
                    pins,
                    hidden,
                    lessons.getOrDefault(r.id(), 0)))
        .toList();
  }

  public SubjectView get(Actor actor, long id) {
    SubjectStore.Row r = visible(actor, id);
    return view(
        actor,
        r,
        subjects.groupsOf(List.of(id)).getOrDefault(id, List.of()),
        subjects.pinned(actor.id()),
        subjects.hidden(actor.id()),
        subjects.lessonCounts(List.of(id), access.visibleGroups(actor)).getOrDefault(id, 0));
  }

  /** Предмет, видимый пользователю, иначе 404 (не раскрываем существование). */
  public SubjectStore.Row visible(Actor actor, long id) {
    SubjectStore.Row r = subjects.find(id).orElseThrow(ApiException::notFound);
    access.requireSee(actor, subjects.groupIds(id));
    return r;
  }

  private SubjectView view(
      Actor actor,
      SubjectStore.Row r,
      List<SubjectStore.GroupRef> gs,
      Set<Long> pins,
      Set<Long> hidden,
      int lessons) {
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
        r.teachers(),
        r.archivedAt() != null,
        pins.contains(r.id()),
        !hidden.contains(r.id()),
        gs,
        new Can(
            access.can(actor, Permission.MANAGE_SUBJECTS, ids),
            access.can(actor, Permission.SHARE_SUBJECTS, ids)),
        lessons);
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
    if (c.teachers() != null) {
      subjects.setTeachers(id, c.teachers());
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
    if (c.teachers() != null) {
      subjects.setTeachers(id, c.teachers());
    }
    return get(actor, id);
  }

  @Transactional
  public void setArchived(Actor actor, long id, boolean archived) {
    requireManage(actor, id);
    subjects.setArchived(id, archived ? clock.millis() : null);
    audit.log(actor, null, archived ? "subject.archive" : "subject.unarchive", "subject", id);
  }

  /**
   * Номер подгруппы в названии пары из расписания: «(2 подгр.)», «2 гр.», «подгруппа 2», «№2»,
   * «(2)». Как в Titles и на фронте (subgroups.ts).
   */
  private static final Pattern SUBGROUP =
      Pattern.compile(
          "(?iu)(?:№\\s*(\\d+)|(\\d+)\\s*(?:-?(?:я|ая)\\s*)?(?:под)?гр(?:уппа|\\.)?(?=[\\s.,)]|$)"
              + "|(?:под)?группа\\s*№?\\s*(\\d+)|\\(\\s*(\\d+)\\s*\\))");

  /**
   * Подпись подгруппы после номера: «Сильная группа». Без цифр – иначе расписание не сопоставит.
   */
  private static final Pattern LABEL = Pattern.compile("[\\p{L}\\p{M} .,'’()-]{0,40}");

  static int subgroupOf(String title) {
    var m = SUBGROUP.matcher(title == null ? "" : title);
    if (!m.find()) {
      return 0;
    }
    for (int g = 1; g <= m.groupCount(); g++) {
      if (m.group(g) != null) {
        return Integer.parseInt(m.group(g));
      }
    }
    return 0;
  }

  /**
   * Разделить на подгруппы (0.7): этот предмет – №1 со всеми заданиями и материалами, остальные –
   * новые с тем же цветом, иконкой и группами; каждый выбирает свою (lib/content/subgroups.ts),
   * чужие у него скрываются («не мой предмет»). С 0.9.5 – подписи (необязательно) идут после
   * номера: «Английский язык №1 Сильная группа» – номер остаётся, и расписание из файла по-прежнему
   * находит свою подгруппу. Пары, у которых в названии из файла стоит номер подгруппы, сразу
   * переходят к своей.
   */
  @Transactional
  public List<SubjectView> split(Actor actor, long id, int count, List<String> labels) {
    requireManage(actor, id);
    if (count < 2 || count > 6) {
      throw ApiException.invalid("count", "Подгрупп – от 2 до 6");
    }
    List<String> names = new java.util.ArrayList<>();
    for (int i = 0; i < count; i++) {
      String l = labels != null && i < labels.size() && labels.get(i) != null ? labels.get(i) : "";
      l = l.strip().replaceAll("\\s+", " ");
      if (!LABEL.matcher(l).matches()) {
        throw ApiException.invalid(
            "names", "Название подгруппы – до 40 букв, без цифр: номер подставится сам");
      }
      names.add(l);
    }
    SubjectStore.Row r = subjects.find(id).orElseThrow(ApiException::notFound);
    String base = r.name().replaceAll("\\s*(№\\s*\\d+|\\(\\s*\\d+\\s*\\)).*$", "").strip();
    if (base.length() > 60) {
      base = base.substring(0, 60).strip();
    }
    long now = clock.millis();
    List<Long> groupIds = subjects.groupIds(id);
    subjects.update(id, title(base, 1, names.get(0)), r.teacher(), r.color());
    List<Long> ids = new java.util.ArrayList<>(List.of(id));
    for (int i = 2; i <= count; i++) {
      long n =
          subjects.insert(
              title(base, i, names.get(i - 1)), r.teacher(), r.color(), actor.id(), now);
      if (r.icon() != null) {
        subjects.setIcon(n, r.icon());
      }
      for (long g : groupIds) {
        subjects.link(n, g, now);
      }
      ids.add(n);
    }
    int moved = 0;
    for (var e : subjects.lessonTitles(id).entrySet()) {
      int k = subgroupOf(e.getValue());
      if (k >= 2 && k <= count) {
        subjects.moveLesson(e.getKey(), ids.get(k - 1), now);
        moved++;
      }
    }
    audit.log(
        actor,
        groupIds.isEmpty() ? null : groupIds.getFirst(),
        "subject.split",
        "subject",
        id,
        Map.of("name", base, "count", count, "lessons", moved));
    return ids.stream().map(x -> get(actor, x)).toList();
  }

  private static String title(String base, int n, String label) {
    return base + " №" + n + (label.isEmpty() ? "" : " " + label);
  }

  public void setPinned(Actor actor, long id, boolean pinned) {
    visible(actor, id);
    if (pinned) {
      subjects.pin(actor.id(), id, clock.millis());
    } else {
      subjects.unpin(actor.id(), id);
    }
  }

  /**
   * «Не мой предмет»: скрыть у себя предмет другой подгруппы — его задания, новости и материалы
   * пропадут из общих лент и уведомлений (на странице предмета всё остаётся) — или вернуть.
   */
  public void setMine(Actor actor, long id, boolean mine) {
    visible(actor, id);
    if (mine) {
      subjects.unhide(actor.id(), id);
    } else {
      subjects.hide(actor.id(), id, clock.millis());
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
      throw ApiException.invalid("teacher", "Имя преподавателя – до 80 символов");
    }
    String color = in.color() == null || in.color().isBlank() ? "#6b7280" : in.color().strip();
    if (!COLOR.matcher(color).matches()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "invalid", "Цвет в формате #rrggbb");
    }
    String chat = in.chatUrl() == null ? null : Telegram.normalize(in.chatUrl(), "chatUrl");
    return new Input(
        name,
        teacher,
        color.toLowerCase(java.util.Locale.ROOT),
        chat,
        icon(in.icon()),
        teachers(in.teachers()));
  }

  /** Преподаватели по видам: известные виды, до 80 символов, без переносов строк. */
  static Map<String, String> teachers(Map<String, String> raw) {
    if (raw == null) {
      return null;
    }
    Map<String, String> out = new java.util.LinkedHashMap<>();
    for (var e : raw.entrySet()) {
      if (!SubjectStore.TEACHER_KINDS.contains(e.getKey())) {
        throw ApiException.invalid("teachers", "Неизвестный вид пары");
      }
      String v = e.getValue() == null ? "" : e.getValue().strip().replaceAll("\\s+", " ");
      if (v.length() > 80 || v.contains("=")) {
        throw ApiException.invalid("teachers", "Имя преподавателя – до 80 символов");
      }
      if (!v.isEmpty()) {
        out.put(e.getKey(), v);
      }
    }
    return out;
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
