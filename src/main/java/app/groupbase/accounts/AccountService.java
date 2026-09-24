package app.groupbase.accounts;

import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Authz;
import app.groupbase.auth.GroupRole;
import app.groupbase.auth.InstanceRole;
import app.groupbase.auth.Passwords;
import app.groupbase.auth.Permission;
import app.groupbase.auth.SessionService;
import app.groupbase.auth.Tokens;
import app.groupbase.config.GroupbaseProperties;
import app.groupbase.store.GroupStore;
import app.groupbase.store.User;
import app.groupbase.store.UserStore;
import app.groupbase.store.UserTokenStore;
import app.groupbase.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Аккаунты: прямое создание, активация, сброс пароля, роли, блокировка и удаление. */
@Service
public class AccountService {

  /** Как выдать доступ созданному аккаунту. */
  public enum Delivery {
    /** Одноразовая ссылка активации: пользователь сам задаёт пароль. */
    LINK,
    /** Временный пароль, который нужно сменить при первом входе. */
    PASSWORD
  }

  public record NewAccount(String username, String displayName) {}

  public record Created(
      long userId,
      String username,
      String displayName,
      String activationPath,
      String temporaryPassword) {}

  public record TokenInfo(String username, String displayName, String purpose) {}

  /** Событие удаления аккаунта: подписчики стирают аватар и прочие файлы пользователя. */
  public record UserDeleted(long userId, String avatar) {}

  private final UserStore users;
  private final GroupStore groups;
  private final UserTokenStore tokens;
  private final SessionService sessions;
  private final Authz authz;
  private final AuditService audit;
  private final InstanceSettings settings;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final Duration linkTtl;

  public AccountService(
      UserStore users,
      GroupStore groups,
      UserTokenStore tokens,
      SessionService sessions,
      Authz authz,
      AuditService audit,
      InstanceSettings settings,
      ApplicationEventPublisher events,
      Clock clock,
      GroupbaseProperties props) {
    this.users = users;
    this.groups = groups;
    this.tokens = tokens;
    this.sessions = sessions;
    this.authz = authz;
    this.audit = audit;
    this.settings = settings;
    this.events = events;
    this.clock = clock;
    this.linkTtl = Duration.ofDays(props.auth().activationDays());
  }

  // --- создание ---

  /**
   * Прямое создание аккаунтов (одного или списком). groupId == null — аккаунт без группы (только
   * admin). Логин генерируется из имени, если не задан.
   */
  @Transactional
  public List<Created> create(
      Actor actor, Long groupId, List<NewAccount> rows, GroupRole role, Delivery delivery) {
    if (!settings.directAccounts()) {
      throw ApiException.forbidden("Прямое создание аккаунтов выключено в настройках");
    }
    authz.require(actor, Permission.CREATE_ACCOUNTS, groupId);
    if (groupId != null) {
      groups.find(groupId).orElseThrow(ApiException::notFound);
      requireCanAssign(actor, role, groupId);
    }
    if (rows.isEmpty() || rows.size() > 300) {
      throw ApiException.badRequest("Список должен содержать от 1 до 300 человек");
    }
    Set<String> batch = new HashSet<>();
    List<Created> out = new ArrayList<>();
    long now = clock.millis();
    for (int i = 0; i < rows.size(); i++) {
      NewAccount row = rows.get(i);
      String displayName;
      String username;
      try {
        displayName = Names.displayName(row.displayName());
        username =
            row.username() == null || row.username().isBlank()
                ? uniqueUsername(Names.suggestUsername(displayName), batch)
                : Names.username(row.username());
      } catch (ApiException e) {
        throw new ApiException(
            e.status(), e.code(), "Строка " + (i + 1) + ": " + e.getMessage(), Map.of("row", i));
      }
      if (!batch.add(username) || users.usernameTaken(username)) {
        throw new ApiException(
            HttpStatus.CONFLICT,
            "username_taken",
            "Строка " + (i + 1) + ": имя «" + username + "» уже занято",
            Map.of("row", i));
      }
      Created c = createOne(actor, username, displayName, delivery, null, now);
      if (groupId != null) {
        groups.addMember(c.userId(), groupId, role, now);
      }
      out.add(c);
    }
    audit.log(
        actor,
        groupId,
        "user.create",
        "user",
        out.size() == 1 ? out.getFirst().userId() : null,
        Map.of("count", out.size(), "role", role == null ? "" : role.id()));
    return out;
  }

  /** Создание аккаунта без проверок прав: для мастера первого запуска и CLI. */
  @Transactional
  public Created createSystem(
      String username,
      String displayName,
      String password,
      InstanceRole instanceRole,
      Long groupId,
      GroupRole groupRole) {
    String u = Names.username(username);
    String d = Names.displayName(displayName);
    if (users.usernameTaken(u)) {
      throw ApiException.conflict("username_taken", "Имя «" + u + "» уже занято");
    }
    long now = clock.millis();
    Created c;
    if (password != null) {
      String err = Passwords.validate(password, u);
      if (err != null) {
        throw ApiException.invalid("password", err);
      }
      long id =
          users.insert(
              u, d, Passwords.hash(password), false, instanceRole, User.Status.ACTIVE, now);
      c = new Created(id, u, d, null, null);
    } else {
      c = createOne(null, u, d, Delivery.LINK, instanceRole, now);
    }
    if (groupId != null && groupRole != null) {
      groups.addMember(c.userId(), groupId, groupRole, now);
    }
    return c;
  }

  private Created createOne(
      Actor actor,
      String username,
      String displayName,
      Delivery delivery,
      InstanceRole instanceRole,
      long now) {
    if (delivery == Delivery.PASSWORD) {
      String temp = Tokens.temporaryPassword();
      long id =
          users.insert(
              username,
              displayName,
              Passwords.hash(temp),
              true,
              instanceRole,
              User.Status.ACTIVE,
              now);
      return new Created(id, username, displayName, null, temp);
    }
    long id =
        users.insert(username, displayName, null, false, instanceRole, User.Status.PENDING, now);
    String token = Tokens.newToken();
    tokens.insert(
        Tokens.sha256(token),
        id,
        UserTokenStore.Purpose.ACTIVATE,
        actor == null ? null : actor.id(),
        now,
        now + linkTtl.toMillis());
    return new Created(id, username, displayName, "/activate/" + token, null);
  }

  private String uniqueUsername(String base, Set<String> batch) {
    String b = base.isEmpty() ? "user" : base;
    String candidate = b;
    for (int n = 2; batch.contains(candidate) || users.usernameTaken(candidate); n++) {
      candidate = b + n;
    }
    return candidate;
  }

  /** Может ли actor выдать эту роль в группе (при создании, инвайте или смене роли). */
  public void requireCanAssign(Actor actor, GroupRole role, long groupId) {
    Permission needed =
        switch (role) {
          case HEADMAN -> Permission.ASSIGN_HEADMAN;
          case DEPUTY -> Permission.ASSIGN_DEPUTY;
          case STUDENT -> null;
        };
    if (needed != null && !authz.can(actor, needed, groupId)) {
      throw ApiException.forbidden("Недостаточно прав, чтобы назначить эту роль");
    }
  }

  // --- активация и сброс ---

  public TokenInfo tokenInfo(String token) {
    UserTokenStore.Row row = validToken(token);
    User u = users.find(row.userId()).orElseThrow(ApiException::notFound);
    return new TokenInfo(u.username(), u.displayName(), row.purpose().id());
  }

  /** Задаёт пароль по ссылке активации или сброса. Возвращает пользователя для входа. */
  @Transactional
  public User redeem(String token, String password) {
    UserTokenStore.Row row = validToken(token);
    User u = users.find(row.userId()).orElseThrow(ApiException::notFound);
    if (u.status() == User.Status.BLOCKED || u.status() == User.Status.DELETED) {
      throw ApiException.forbidden("Аккаунт заблокирован");
    }
    String err = Passwords.validate(password, u.username());
    if (err != null) {
      throw ApiException.invalid("password", err);
    }
    if (!tokens.markUsed(Tokens.sha256(token), clock.millis())) {
      throw linkInvalid();
    }
    users.setPassword(u.id(), Passwords.hash(password), false);
    sessions.revokeAll(u.id());
    audit.log(null, null, "user." + row.purpose().id(), "user", u.id());
    return users.find(u.id()).orElseThrow();
  }

  private UserTokenStore.Row validToken(String token) {
    if (!Tokens.looksValid(token)) {
      throw linkInvalid();
    }
    UserTokenStore.Row row = tokens.find(Tokens.sha256(token)).orElseThrow(this::linkInvalid);
    if (row.usedAt() != null || row.expiresAt() <= clock.millis()) {
      throw linkInvalid();
    }
    return row;
  }

  private ApiException linkInvalid() {
    return new ApiException(
        HttpStatus.GONE,
        "link_invalid",
        "Ссылка недействительна или устарела. Попросите старосту выдать новую");
  }

  /** Одноразовая ссылка сброса пароля (староста или admin). */
  @Transactional
  public String resetLink(Actor actor, long targetId, Long groupId) {
    authz.require(actor, Permission.RESET_PASSWORDS, groupId);
    User target = manageable(actor, targetId, groupId);
    String token = Tokens.newToken();
    long now = clock.millis();
    tokens.insert(
        Tokens.sha256(token),
        target.id(),
        UserTokenStore.Purpose.RESET,
        actor.id(),
        now,
        now + linkTtl.toMillis());
    audit.log(actor, groupId, "user.reset_link", "user", target.id());
    return "/activate/" + token;
  }

  /** Смена своего пароля. В ограниченной сессии (временный пароль) текущий не спрашивается. */
  @Transactional
  public void changePassword(Actor actor, String current, String next) {
    User u = users.find(actor.id()).orElseThrow(ApiException::unauthorized);
    // Старый пароль не спрашиваем при обязательной смене и в окне приложения хоста: кто сидит за
    // компьютером сервера, и так владеет всеми данными — а забытый пароль иначе не вернуть.
    boolean forced =
        actor.restriction() == Actor.Restriction.PASSWORD_CHANGE_REQUIRED || actor.local();
    if (!forced && !Passwords.verify(current, u.passwordHash())) {
      throw ApiException.invalid("current", "Текущий пароль неверный");
    }
    String err = Passwords.validate(next, u.username());
    if (err != null) {
      throw ApiException.invalid("password", err);
    }
    if (Passwords.verify(next, u.passwordHash())) {
      throw ApiException.invalid("password", "Новый пароль совпадает со старым");
    }
    users.setPassword(u.id(), Passwords.hash(next), false);
    sessions.revokeOthers(u.id(), actor.sessionHash());
    audit.log(actor, null, "user.password_change", "user", u.id());
  }

  public void rename(Actor actor, String displayName) {
    users.setDisplayName(actor.id(), Names.displayName(displayName));
  }

  // --- роли ---

  @Transactional
  public void setGroupRole(Actor actor, long groupId, long targetId, GroupRole role) {
    GroupRole current = groups.role(targetId, groupId).orElseThrow(() -> notMember());
    if (current == role) {
      return;
    }
    if (targetId == actor.id() && !actor.isAdmin()) {
      throw ApiException.forbidden("Нельзя менять собственную роль");
    }
    requireCanAssign(actor, current, groupId);
    requireCanAssign(actor, role, groupId);
    groups.setRole(targetId, groupId, role);
    audit.log(actor, groupId, "member.role", "user", targetId, Map.of("role", role.id()));
  }

  @Transactional
  public void setInstanceRole(Actor actor, long targetId, InstanceRole role) {
    User target = users.find(targetId).orElseThrow(ApiException::notFound);
    Permission needed =
        (role == InstanceRole.ADMIN || target.instanceRole() == InstanceRole.ADMIN)
            ? Permission.MANAGE_INSTANCE
            : Permission.ASSIGN_MODERATOR;
    authz.require(actor, needed, null);
    if (target.instanceRole() == InstanceRole.ADMIN && role != InstanceRole.ADMIN) {
      requireAnotherAdmin(targetId);
    }
    users.setInstanceRole(targetId, role);
    sessions.revokeAll(targetId);
    audit.log(
        actor,
        null,
        "user.instance_role",
        "user",
        targetId,
        Map.of("role", role == null ? "" : role.id()));
  }

  @Transactional
  public void removeFromGroup(Actor actor, long groupId, long targetId) {
    if (targetId != actor.id()) {
      authz.require(actor, Permission.BLOCK_USERS, groupId);
      manageable(actor, targetId, groupId);
    } else if (groups.role(targetId, groupId).isEmpty()) {
      throw notMember();
    }
    groups.removeMember(targetId, groupId);
    audit.log(actor, groupId, "member.remove", "user", targetId);
  }

  // --- блокировка и удаление ---

  @Transactional
  public void setBlocked(Actor actor, long targetId, Long groupId, boolean blocked) {
    authz.require(actor, Permission.BLOCK_USERS, groupId);
    User target = manageable(actor, targetId, groupId);
    if (target.status() == User.Status.DELETED) {
      throw ApiException.notFound();
    }
    if (blocked) {
      users.setStatus(targetId, User.Status.BLOCKED);
      sessions.revokeAll(targetId);
    } else if (target.status() == User.Status.BLOCKED) {
      users.setStatus(
          targetId, target.passwordHash() == null ? User.Status.PENDING : User.Status.ACTIVE);
    }
    audit.log(actor, groupId, blocked ? "user.block" : "user.unblock", "user", targetId);
  }

  /** Удаление чужого аккаунта (модерация). Контент остаётся с подписью «удалённый пользователь». */
  @Transactional
  public void deleteUser(Actor actor, long targetId, Long groupId) {
    authz.require(actor, Permission.BLOCK_USERS, groupId);
    User target = manageable(actor, targetId, groupId);
    wipe(target);
    audit.log(actor, groupId, "user.delete", "user", targetId);
  }

  /** Удаление своего аккаунта по паролю. */
  @Transactional
  public void deleteSelf(Actor actor, String password) {
    User u = users.find(actor.id()).orElseThrow(ApiException::unauthorized);
    if (!Passwords.verify(password, u.passwordHash())) {
      throw ApiException.invalid("password", "Неверный пароль");
    }
    if (u.instanceRole() == InstanceRole.ADMIN) {
      requireAnotherAdmin(u.id());
    }
    wipe(u);
    audit.log(null, null, "user.delete_self", "user", u.id());
  }

  private void wipe(User u) {
    groups.removeAllMemberships(u.id());
    sessions.revokeAll(u.id());
    tokens.deleteForUser(u.id());
    users.anonymize(u.id(), clock.millis());
    events.publishEvent(new UserDeleted(u.id(), u.avatar()));
  }

  private void requireAnotherAdmin(long exceptId) {
    boolean another =
        users.listStaff().stream()
            .anyMatch(
                x ->
                    x.id() != exceptId
                        && x.instanceRole() == InstanceRole.ADMIN
                        && x.status() == User.Status.ACTIVE);
    if (!another) {
      throw ApiException.conflict(
          "last_admin", "Это последний администратор. Сначала назначьте другого");
    }
  }

  /**
   * Пользователь, над которым actor может выполнять модерацию: в контексте группы — только её
   * участник, и ранг actor должен быть строго выше максимального ранга цели во всём инстансе.
   */
  private User manageable(Actor actor, long targetId, Long groupId) {
    if (targetId == actor.id()) {
      throw ApiException.forbidden("Это действие нельзя применить к себе");
    }
    User target = users.find(targetId).orElseThrow(ApiException::notFound);
    if (groupId != null && groups.role(targetId, groupId).isEmpty()) {
      throw notMember();
    }
    int actorRank = authz.rankIn(actor.id(), actor.instanceRole(), groupId);
    int targetRank = authz.maxRank(targetId, target.instanceRole());
    if (actorRank <= targetRank) {
      throw ApiException.forbidden(
          "Нельзя применить это действие к пользователю с такой же или более высокой ролью");
    }
    return target;
  }

  private static ApiException notMember() {
    return new ApiException(HttpStatus.NOT_FOUND, "not_member", "Пользователь не состоит в группе");
  }
}
