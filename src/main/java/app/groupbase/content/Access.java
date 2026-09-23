package app.groupbase.content;

import app.groupbase.auth.Actor;
import app.groupbase.auth.Authz;
import app.groupbase.auth.Permission;
import app.groupbase.web.ApiException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Видимость контента по группам: элемент виден, если хотя бы одна его целевая группа видна. */
@Service
public class Access {

  private final Authz authz;

  public Access(Authz authz) {
    this.authz = authz;
  }

  public List<Long> visibleGroups(Actor actor) {
    return authz.visibleGroupIds(actor);
  }

  /** Видимые группы, при необходимости суженные до одной (фильтр в интерфейсе). */
  public List<Long> scope(Actor actor, Long onlyGroup) {
    List<Long> visible = visibleGroups(actor);
    if (onlyGroup == null) {
      return visible;
    }
    return visible.contains(onlyGroup) ? List.of(onlyGroup) : List.of();
  }

  public Set<Long> groupsWith(Actor actor, Permission p, Collection<Long> among) {
    Set<Long> out = new LinkedHashSet<>();
    for (Long g : among) {
      if (authz.can(actor, p, g)) {
        out.add(g);
      }
    }
    return out;
  }

  public boolean canSee(Actor actor, Collection<Long> targets) {
    return authz.canAny(actor, Permission.VIEW_GROUP, targets);
  }

  public void requireSee(Actor actor, Collection<Long> targets) {
    if (!canSee(actor, targets)) {
      throw ApiException.notFound();
    }
  }

  public boolean can(Actor actor, Permission p, Collection<Long> anyOf) {
    return authz.canAny(actor, p, anyOf);
  }

  public void requireAll(Actor actor, Permission p, Collection<Long> groups) {
    authz.requireAll(actor, p, groups);
  }
}
