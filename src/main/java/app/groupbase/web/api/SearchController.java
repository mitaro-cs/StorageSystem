package app.groupbase.web.api;

import app.groupbase.auth.Actor;
import app.groupbase.search.SearchService;
import app.groupbase.web.ApiException;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Поиск по всему, что видно пользователю. */
@RestController
class SearchController {

  private static final Set<String> KINDS = Set.of("homework", "news", "material", "subject");

  private final SearchService search;

  SearchController(SearchService search) {
    this.search = search;
  }

  @GetMapping("/api/search")
  SearchService.Result search(
      Actor actor,
      @RequestParam String q,
      @RequestParam(required = false) Long group,
      @RequestParam(required = false) String kind) {
    if (q.length() > 200) {
      throw ApiException.invalid("q", "Запрос слишком длинный");
    }
    if (kind != null && !KINDS.contains(kind)) {
      throw ApiException.invalid("kind", "Неизвестный раздел поиска");
    }
    return search.search(actor, q, group, kind);
  }
}
