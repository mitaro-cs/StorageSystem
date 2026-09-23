package app.groupbase.web.api;

import app.groupbase.auth.SessionService;
import app.groupbase.store.User;
import app.groupbase.web.Cookies;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Общие действия контроллеров: выдать сессию после входа. */
@Component
public class Http {

  private final SessionService sessions;
  private final Cookies cookies;

  public Http(SessionService sessions, Cookies cookies) {
    this.sessions = sessions;
    this.cookies = cookies;
  }

  public Map<String, String> startSession(User user, HttpServletResponse res) {
    String token = sessions.create(user.id());
    cookies.setSession(res, token, sessions.ttlSeconds());
    return Map.of("status", "ok");
  }
}
