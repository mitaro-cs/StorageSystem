package app.groupbase.web.api;

import app.groupbase.auth.Actor;
import app.groupbase.content.HomeworkService;
import app.groupbase.content.NewsService;
import app.groupbase.schedule.LessonService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Экран «Сегодня» одним запросом: пары сегодня и завтра, ближайшие дедлайны, просрочка, свежие
 * новости и зачёты с экзаменами (для карточки сессии).
 */
@RestController
class TodayController {

  record Today(
      List<HomeworkService.Item> upcoming,
      List<HomeworkService.Item> overdue,
      List<NewsService.Item> pinned,
      List<NewsService.Item> news,
      List<HomeworkService.Item> exams,
      List<LessonService.Lesson> lessons) {}

  private final HomeworkService homework;
  private final NewsService news;
  private final LessonService lessons;

  TodayController(HomeworkService homework, NewsService news, LessonService lessons) {
    this.homework = homework;
    this.news = news;
    this.lessons = lessons;
  }

  @GetMapping("/api/today")
  Today today(Actor actor, @RequestParam(required = false) Long group) {
    var feed = news.feed(actor, group, null, null, 5);
    return new Today(
        homework.list(actor, HomeworkService.View.WEEK, group, null, null, null, null),
        homework.list(actor, HomeworkService.View.OVERDUE, group, null, null, null, null),
        feed.pinned(),
        feed.items(),
        homework.list(actor, HomeworkService.View.EXAMS, group, null, null, null, null),
        lessons.today(actor, group));
  }
}
