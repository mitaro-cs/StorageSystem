package app.groupbase.web;

import app.groupbase.auth.Actor;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Параметр контроллера типа {@link Actor} — текущий пользователь (null для @Public без входа). */
@Component
class CurrentActorResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter p) {
    return p.getParameterType() == Actor.class;
  }

  @Override
  public Object resolveArgument(
      MethodParameter p,
      ModelAndViewContainer mav,
      NativeWebRequest req,
      WebDataBinderFactory binders) {
    return req.getAttribute(AuthFilter.ACTOR, RequestAttributes.SCOPE_REQUEST);
  }
}
