package app.groupbase.web;

import app.groupbase.auth.Actor;
import app.groupbase.auth.Authz;
import app.groupbase.auth.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Единственное место, где проверяется доступ к эндпоинтам: сессия, ограниченная сессия и {@link
 * Require} с группой из пути. Всё, что не помечено {@link Public}, требует входа.
 */
@Component
class AuthInterceptor implements HandlerInterceptor {

  static final String GROUP_VAR = "groupId";

  private final Authz authz;

  AuthInterceptor(Authz authz) {
    this.authz = authz;
  }

  @Override
  public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
    if (!(handler instanceof HandlerMethod hm)) {
      return true;
    }
    if (hm.hasMethodAnnotation(Public.class)
        || hm.getBeanType().isAnnotationPresent(Public.class)) {
      return true;
    }
    Actor actor = (Actor) req.getAttribute(AuthFilter.ACTOR);
    if (actor == null) {
      throw ApiException.unauthorized();
    }
    if (actor.restriction() != null && !hm.hasMethodAnnotation(AllowRestricted.class)) {
      String msg =
          actor.restriction() == Actor.Restriction.PASSWORD_CHANGE_REQUIRED
              ? "Сначала смените временный пароль"
              : "Сначала включите двухфакторную аутентификацию";
      throw new ApiException(HttpStatus.FORBIDDEN, actor.restriction().id(), msg);
    }
    Long groupId = groupFromPath(req);
    Require require = hm.getMethodAnnotation(Require.class);
    Permission needed =
        require != null ? require.value() : (groupId != null ? Permission.VIEW_GROUP : null);
    if (needed != null) {
      authz.require(actor, needed, needed.instanceLevel ? null : groupId);
    }
    return true;
  }

  @SuppressWarnings("unchecked")
  private static Long groupFromPath(HttpServletRequest req) {
    Map<String, String> vars =
        (Map<String, String>) req.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    if (vars == null || !vars.containsKey(GROUP_VAR)) {
      return null;
    }
    try {
      return Long.parseLong(vars.get(GROUP_VAR));
    } catch (NumberFormatException e) {
      throw ApiException.notFound();
    }
  }
}
