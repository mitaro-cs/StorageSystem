package app.groupbase.web.api;

import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Permission;
import app.groupbase.content.Telegram;
import app.groupbase.store.GroupChatStore;
import app.groupbase.web.ApiException;
import app.groupbase.web.Require;
import java.time.Clock;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Закреплённые чаты группы в Telegram. Их видят все участники (в {@code /api/me} у группы — на
 * главной), закрепляют те, кто может публиковать новости.
 */
@RestController
@RequestMapping("/api/groups/{groupId}/chats")
class GroupChatController {

  static final int MAX = 8;

  record ChatBody(String title, String url) {}

  record ChatView(long id, String title, String url) {}

  private final GroupChatStore chats;
  private final AuditService audit;
  private final Clock clock;

  GroupChatController(GroupChatStore chats, AuditService audit, Clock clock) {
    this.chats = chats;
    this.audit = audit;
    this.clock = clock;
  }

  static List<ChatView> views(List<GroupChatStore.Chat> list) {
    return list.stream().map(c -> new ChatView(c.id(), c.title(), c.url())).toList();
  }

  @Require(Permission.PUBLISH_NEWS)
  @PostMapping
  List<ChatView> add(Actor actor, @PathVariable long groupId, @RequestBody ChatBody b) {
    if (chats.count(groupId) >= MAX) {
      throw ApiException.conflict("too_many", "Закрепить можно до " + MAX + " чатов");
    }
    String url = url(b);
    chats.insert(groupId, title(b), url, actor.id(), clock.millis());
    audit.log(actor, groupId, "group.chat_add", "group", groupId);
    return views(chats.list(groupId));
  }

  @Require(Permission.PUBLISH_NEWS)
  @PutMapping("/{id}")
  List<ChatView> update(
      Actor actor, @PathVariable long groupId, @PathVariable long id, @RequestBody ChatBody b) {
    chats.find(groupId, id).orElseThrow(ApiException::notFound);
    chats.update(id, title(b), url(b));
    audit.log(actor, groupId, "group.chat_update", "group", groupId);
    return views(chats.list(groupId));
  }

  @Require(Permission.PUBLISH_NEWS)
  @DeleteMapping("/{id}")
  List<ChatView> delete(Actor actor, @PathVariable long groupId, @PathVariable long id) {
    chats.find(groupId, id).orElseThrow(ApiException::notFound);
    chats.delete(id);
    audit.log(actor, groupId, "group.chat_remove", "group", groupId);
    return views(chats.list(groupId));
  }

  private static String title(ChatBody b) {
    String t = b.title() == null ? "" : b.title().strip();
    if (t.length() > 40) {
      throw ApiException.invalid("title", "Название — до 40 символов");
    }
    return t.isEmpty() ? "Чат группы" : t;
  }

  private static String url(ChatBody b) {
    String url = Telegram.normalize(b.url(), "url");
    if (url.isEmpty()) {
      throw ApiException.invalid("url", "Вставьте ссылку на чат: t.me/… или @имя");
    }
    return url;
  }
}
