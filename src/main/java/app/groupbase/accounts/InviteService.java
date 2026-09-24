package app.groupbase.accounts;

import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.GroupRole;
import app.groupbase.auth.Passwords;
import app.groupbase.auth.Tokens;
import app.groupbase.store.Group;
import app.groupbase.store.GroupStore;
import app.groupbase.store.Invite;
import app.groupbase.store.InviteStore;
import app.groupbase.store.User;
import app.groupbase.store.UserStore;
import app.groupbase.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Инвайт-ссылки: одно- или многоразовые, с TTL, лимитом и ролью в группе. */
@Service
public class InviteService {

  public record Created(Invite invite, String path) {}

  public record Info(
      long groupId, String groupName, String university, GroupRole role, boolean valid) {}

  /** Итог вступления: already — человек уже был в группе (ссылку открыли на втором устройстве). */
  public record Joined(long groupId, boolean already) {}

  private static final Duration MAX_TTL = Duration.ofDays(90);

  private final InviteStore invites;
  private final GroupStore groups;
  private final UserStore users;
  private final AccountService accounts;
  private final InstanceSettings settings;
  private final AuditService audit;
  private final Clock clock;

  public InviteService(
      InviteStore invites,
      GroupStore groups,
      UserStore users,
      AccountService accounts,
      InstanceSettings settings,
      AuditService audit,
      Clock clock) {
    this.invites = invites;
    this.groups = groups;
    this.users = users;
    this.accounts = accounts;
    this.settings = settings;
    this.audit = audit;
    this.clock = clock;
  }

  /** Права CREATE_INVITES проверяет интерсептор по {groupId}; здесь — роль и настройки. */
  @Transactional
  public Created create(
      Actor actor, long groupId, GroupRole role, Integer maxUses, int ttlHours, String note) {
    if (!settings.invites()) {
      throw ApiException.forbidden("Инвайт-ссылки выключены в настройках");
    }
    groups.find(groupId).orElseThrow(ApiException::notFound);
    accounts.requireCanAssign(actor, role, groupId);
    if (maxUses != null && (maxUses < 1 || maxUses > 1000)) {
      throw ApiException.invalid("maxUses", "Лимит использований: от 1 до 1000");
    }
    if (ttlHours < 1 || ttlHours > MAX_TTL.toHours()) {
      throw ApiException.invalid("ttlHours", "Срок действия: от 1 часа до 90 дней");
    }
    String n = note == null ? "" : note.strip();
    if (n.length() > 100) {
      throw ApiException.invalid("note", "Заметка — до 100 символов");
    }
    String token = Tokens.newToken();
    long now = clock.millis();
    long id =
        invites.insert(
            Tokens.sha256(token),
            groupId,
            role,
            maxUses,
            now + Duration.ofHours(ttlHours).toMillis(),
            n,
            actor.id(),
            now);
    audit.log(actor, groupId, "invite.create", "invite", id, Map.of("role", role.id()));
    return new Created(invites.find(id).orElseThrow(), "/invite/" + token);
  }

  public List<Invite> list(long groupId) {
    return invites.list(groupId);
  }

  @Transactional
  public void revoke(Actor actor, long groupId, long inviteId) {
    Invite inv = invites.find(inviteId).orElseThrow(ApiException::notFound);
    if (inv.groupId() != groupId) {
      throw ApiException.notFound();
    }
    invites.revoke(inviteId, clock.millis());
    audit.log(actor, groupId, "invite.revoke", "invite", inviteId);
  }

  public Info info(String token) {
    Invite inv = find(token);
    Group g = groups.find(inv.groupId()).orElseThrow(this::invalid);
    return new Info(g.id(), g.name(), g.university(), inv.role(), inv.usable(clock.millis()));
  }

  /** Регистрация нового пользователя по инвайту. */
  @Transactional
  public User accept(String token, String username, String displayName, String password) {
    Invite inv = usable(token);
    String u = Names.username(username);
    String err = Passwords.validate(password, u);
    if (err != null) {
      throw ApiException.invalid("password", err);
    }
    if (users.usernameTaken(u)) {
      throw ApiException.conflict("username_taken", "Имя «" + u + "» уже занято");
    }
    if (!invites.consume(inv.id(), clock.millis())) {
      throw invalid();
    }
    AccountService.Created c =
        accounts.createSystem(u, displayName, password, null, inv.groupId(), inv.role());
    audit.log(null, inv.groupId(), "invite.accept", "user", c.userId(), Map.of("invite", inv.id()));
    return users.find(c.userId()).orElseThrow();
  }

  /**
   * Уже вошедший пользователь вступает ещё в одну группу. Если он уже в ней — это не ошибка: ту же
   * ссылку часто открывают со второго устройства, где человек уже вошёл. Приглашение при этом не
   * расходуется.
   */
  @Transactional
  public Joined join(Actor actor, String token) {
    Invite inv = usable(token);
    if (groups.role(actor.id(), inv.groupId()).isPresent()) {
      return new Joined(inv.groupId(), true);
    }
    long now = clock.millis();
    if (!invites.consume(inv.id(), now)) {
      throw invalid();
    }
    groups.addMember(actor.id(), inv.groupId(), inv.role(), now);
    audit.log(actor, inv.groupId(), "invite.join", "user", actor.id(), Map.of("invite", inv.id()));
    return new Joined(inv.groupId(), false);
  }

  private Invite usable(String token) {
    if (!settings.invites()) {
      throw ApiException.forbidden("Инвайт-ссылки выключены в настройках");
    }
    Invite inv = find(token);
    if (!inv.usable(clock.millis())) {
      throw invalid();
    }
    return inv;
  }

  private Invite find(String token) {
    if (!Tokens.looksValid(token)) {
      throw invalid();
    }
    return invites.findByHash(Tokens.sha256(token)).orElseThrow(this::invalid);
  }

  private ApiException invalid() {
    return new ApiException(
        HttpStatus.GONE, "invite_invalid", "Приглашение недействительно, истекло или исчерпано");
  }
}
