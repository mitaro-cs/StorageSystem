package app.groupbase.web.api;

import app.groupbase.auth.SessionService;
import app.groupbase.config.GroupbaseProperties;
import app.groupbase.store.User;
import app.groupbase.web.Cookies;
import app.groupbase.web.Requests;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Общие действия контроллеров: выдать сессию после входа. */
@Component
public class Http {

  private final SessionService sessions;
  private final Cookies cookies;
  private final boolean desktop;

  public Http(SessionService sessions, Cookies cookies, GroupbaseProperties props) {
    this.sessions = sessions;
    this.cookies = cookies;
    this.desktop = props.desktop().enabled();
  }

  public Map<String, String> startSession(User user, HttpServletResponse res) {
    HttpServletRequest req = Requests.current();
    // На компьютере хоста вход — локальная сессия: она действует только с этого компьютера.
    boolean local = desktop && Requests.fromThisComputer(req);
    String token = sessions.create(user.id(), local);
    cookies.setSession(req, res, token, sessions.ttlSeconds());
    return Map.of("status", "ok");
  }
}
