package app.groupbase.web.api;

import app.groupbase.auth.Actor;
import app.groupbase.content.HomeworkService;
import app.groupbase.content.NewsService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Экран «Сегодня» одним запросом: ближайшие дедлайны, просрочка и свежие новости. */
@RestController
class TodayController {

  record Today(
      List<HomeworkService.Item> upcoming,
      List<HomeworkService.Item> overdue,
      List<NewsService.Item> pinned,
      List<NewsService.Item> news) {}

  private final HomeworkService homework;
  private final NewsService news;

  TodayController(HomeworkService homework, NewsService news) {
    this.homework = homework;
    this.news = news;
  }

  @GetMapping("/api/today")
  Today today(Actor actor, @RequestParam(required = false) Long group) {
    var feed = news.feed(actor, group, null, null, 5);
    return new Today(
        homework.list(actor, HomeworkService.View.WEEK, group, null, null, null, null),
        homework.list(actor, HomeworkService.View.OVERDUE, group, null, null, null, null),
        feed.pinned(),
        feed.items());
  }
}
