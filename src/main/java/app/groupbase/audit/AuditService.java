package app.groupbase.audit;

import app.groupbase.auth.Actor;
import app.groupbase.store.AuditStore;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/** Журнал аудита: кто, что, когда. IP хранится 30 дней. */
@Service
public class AuditService {

  public static final Duration IP_RETENTION = Duration.ofDays(30);

  private final AuditStore store;
  private final Clock clock;
  private final JsonMapper json = JsonMapper.builder().build();

  public AuditService(AuditStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public void log(Actor actor, Long groupId, String action, String targetType, Long targetId) {
    log(actor, groupId, action, targetType, targetId, Map.of());
  }

  public void log(
      Actor actor,
      Long groupId,
      String action,
      String targetType,
      Long targetId,
      Map<String, ?> details) {
    store.insert(
        clock.millis(),
        actor == null ? null : actor.id(),
        groupId,
        action,
        targetType,
        targetId,
        details.isEmpty() ? null : json.writeValueAsString(details),
        currentIp());
  }

  public List<AuditStore.Entry> list(List<Long> groupIds, Long beforeId, int limit) {
    return store.list(groupIds, beforeId, limit);
  }

  /** Только эти действия — журнал модерации. */
  public List<AuditStore.Entry> list(
      List<Long> groupIds, List<String> actions, Long beforeId, int limit) {
    return store.list(groupIds, actions, beforeId, limit);
  }

  public int forgetOldIps() {
    return store.forgetIps(clock.millis() - IP_RETENTION.toMillis());
  }

  private static String currentIp() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
      HttpServletRequest req = a.getRequest();
      return req.getRemoteAddr();
    }
    return null;
  }
}
