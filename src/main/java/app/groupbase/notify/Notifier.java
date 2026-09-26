package app.groupbase.notify;

import app.groupbase.auth.Authz;
import app.groupbase.auth.Permission;
import app.groupbase.auth.Rbac;
import app.groupbase.config.GroupbaseProperties;
import app.groupbase.content.HomeworkService;
import app.groupbase.content.MaterialService;
import app.groupbase.content.NewsService;
import app.groupbase.content.SubjectStore;
import app.groupbase.moderation.ModerationService;
import app.groupbase.notify.NotificationPrefs.Prefs;
import app.groupbase.notify.PushSender.Message;
import app.groupbase.store.GroupStore;
import app.groupbase.store.Member;
import app.groupbase.store.User;
import app.groupbase.store.UserStore;
import app.groupbase.sync.LiveUpdates;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Кому и что сообщить. В приложении (колокольчик) уведомление видят все адресаты, push — только те,
 * кто включил такой вид в настройках. Рассылка — после коммита (откатившаяся публикация никого не
 * тревожит) и в отдельном потоке: sqlite-jdbc после коммита держит блокировку записи, пока
 * соединение не вернётся в пул, да и ответ на публикацию не должен ждать рассылки.
 */
@Service
public class Notifier implements DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(Notifier.class);
  private final ExecutorService async = Executors.newVirtualThreadPerTaskExecutor();

  static final Locale RU = Locale.forLanguageTag("ru");
  private static final DateTimeFormatter DUE = DateTimeFormatter.ofPattern("d MMMM, HH:mm", RU);

  private final NotificationStore store;
  private final PushSubscriptions subs;
  private final NotificationPrefs prefs;
  private final PushSender push;
  private final GroupStore groups;
  private final SubjectStore subjects;
  private final Authz authz;
  private final UserStore users;
  private final LiveUpdates live;
  private final GroupbaseProperties props;
  private final Clock clock;

  public Notifier(
      NotificationStore store,
      PushSubscriptions subs,
      NotificationPrefs prefs,
      PushSender push,
      GroupStore groups,
      SubjectStore subjects,
      Authz authz,
      UserStore users,
      LiveUpdates live,
      GroupbaseProperties props,
      Clock clock) {
    this.store = store;
    this.subs = subs;
    this.prefs = prefs;
    this.push = push;
    this.groups = groups;
    this.subjects = subjects;
    this.authz = authz;
    this.users = users;
    this.live = live;
    this.props = props;
    this.clock = clock;
  }

  /** В колокольчик — всем адресатам; push — тем, чьи настройки это разрешают. */
  public void deliver(Collection<Long> users, Message m, Predicate<Prefs> wantsPush) {
    if (users.isEmpty()) {
      return;
    }
    store.insert(users, m.kind(), m.title(), m.body(), m.url(), clock.millis());
    live.bell(users);
    pushOnly(users, m, wantsPush);
  }

  public void pushOnly(Collection<Long> users, Message m, Predicate<Prefs> wantsPush) {
    if (!push.enabled() || users.isEmpty()) {
      return;
    }
    Map<Long, Prefs> p = prefs.forUsers(users);
    List<Long> want = users.stream().filter(u -> wantsPush.test(p.get(u))).toList();
    push.sendAsync(subs.forUsers(want), m);
  }

  /** Активные участники групп, кроме автора. */
  Set<Long> members(Collection<Long> groupIds, long except) {
    Set<Long> out = new LinkedHashSet<>();
    for (Long g : groupIds) {
      for (Member m : groups.members(g)) {
        if (m.status() == User.Status.ACTIVE && m.userId() != except) {
          out.add(m.userId());
        }
      }
    }
    return out;
  }

  /**
   * Те, кто модерирует в этих группах: по роли в группе (староста) и администраторы и модераторы
   * сайта — они модерируют везде, даже не состоя в группе.
   */
  Set<Long> moderators(Collection<Long> groupIds, long except) {
    Set<Long> out = new LinkedHashSet<>();
    for (Long g : groupIds) {
      for (Member m : groups.members(g)) {
        if (m.status() == User.Status.ACTIVE
            && m.userId() != except
            && authz.roleAllows(m.role(), Permission.MODERATE_CONTENT, g)) {
          out.add(m.userId());
        }
      }
    }
    for (User u : users.listStaff()) {
      if (u.status() == User.Status.ACTIVE
          && u.id() != except
          && Rbac.instanceAllows(u.instanceRole(), Permission.MODERATE_CONTENT)) {
        out.add(u.id());
      }
    }
    return out;
  }

  /** Рассылка в фоне; ошибка не должна теряться молча. */
  private void later(Runnable task) {
    async.execute(
        () -> {
          try {
            task.run();
          } catch (RuntimeException ex) {
            log.error("Не удалось разослать уведомления", ex);
          }
        });
  }

  /** Начатые рассылки дописываются до закрытия базы: иначе при остановке они падают. */
  @Override
  public void destroy() throws InterruptedException {
    async.shutdown();
    if (!async.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
      async.shutdownNow();
    }
  }

  String due(long ms) {
    return DUE.format(Instant.ofEpochMilli(ms).atZone(props.timezone()));
  }

  /** «Задача — сдать до 3 октября, 23:59»; у зачёта и экзамена — когда и где. */
  String homeworkText(HomeworkService.Published e) {
    boolean exam = e.kind() == HomeworkService.Kind.CREDIT || e.kind() == HomeworkService.Kind.EXAM;
    if (!exam) {
      return e.title() + " — сдать до " + due(e.dueAt());
    }
    return e.title()
        + " — "
        + due(e.dueAt())
        + (e.place() == null || e.place().isBlank() ? "" : ", " + e.place());
  }

  @TransactionalEventListener(fallbackExecution = true)
  public void onHomework(HomeworkService.Published e) {
    later(
        () ->
            deliver(
                members(e.groups(), e.authorId()),
                new Message(
                    "homework",
                    e.kind().announce() + " · " + e.subjectName(),
                    homeworkText(e),
                    "/homework/" + e.id(),
                    false),
                Prefs::homework));
  }

  @TransactionalEventListener(fallbackExecution = true)
  public void onNews(NewsService.Published e) {
    later(
        () ->
            deliver(
                members(e.groups(), e.authorId()),
                new Message(
                    "news",
                    e.urgent() ? "Срочно: " + e.title() : e.title(),
                    e.urgent() ? "Срочная новость группы" : "Новость группы",
                    "/news/" + e.id(),
                    e.urgent()),
                p -> p.wantsNews(e.urgent())));
  }

  @TransactionalEventListener(fallbackExecution = true)
  public void onMaterial(MaterialService.Submitted e) {
    later(() -> material(e));
  }

  private void material(MaterialService.Submitted e) {
    String subject = subjects.find(e.subjectId()).map(SubjectStore.Row::name).orElse("Предмет");
    List<Long> groupIds = subjects.groupIds(e.subjectId());
    if ("pending".equals(e.status())) {
      deliver(
          moderators(groupIds, e.authorId()),
          new Message(
              "material_pending",
              "Материал на проверку · " + subject,
              e.title(),
              "/moderation",
              false),
          p -> true);
    } else if ("published".equals(e.status())) {
      deliver(
          members(groupIds, e.authorId()),
          new Message(
              "material", "Новый материал · " + subject, e.title(), "/materials/" + e.id(), false),
          Prefs::materials);
    }
  }

  /** Жалоба — модераторам этих групп: в колокольчик и push (как материал на проверку). */
  @TransactionalEventListener(fallbackExecution = true)
  public void onReport(ModerationService.Reported e) {
    later(
        () ->
            deliver(
                moderators(e.groups(), e.reporterId()),
                new Message(
                    "report",
                    "Жалоба: " + e.title(),
                    e.reason().isBlank() ? "Без пояснения" : e.reason(),
                    "/moderation",
                    false),
                p -> true));
  }
}
