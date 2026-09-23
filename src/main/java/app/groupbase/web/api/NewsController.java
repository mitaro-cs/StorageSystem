package app.groupbase.web.api;

import app.groupbase.auth.Actor;
import app.groupbase.content.CommentService;
import app.groupbase.content.NewsService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class NewsController {

  record FlagBody(Boolean value) {}

  record CommentBody(String body) {}

  private final NewsService news;
  private final CommentService comments;

  NewsController(NewsService news, CommentService comments) {
    this.news = news;
    this.comments = comments;
  }

  @GetMapping("/api/news")
  NewsService.Page feed(
      Actor actor,
      @RequestParam(required = false) Long group,
      @RequestParam(required = false) Long subject,
      @RequestParam(required = false) Long before,
      @RequestParam(defaultValue = "20") int limit) {
    return news.feed(actor, group, subject, before, Math.clamp(limit, 1, 50));
  }

  @PostMapping("/api/news")
  NewsService.Item create(Actor actor, @RequestBody NewsService.Input in) {
    return news.create(actor, in);
  }

  @GetMapping("/api/news/{id}")
  NewsService.Item get(Actor actor, @PathVariable long id) {
    return news.get(actor, id);
  }

  @PatchMapping("/api/news/{id}")
  NewsService.Item update(Actor actor, @PathVariable long id, @RequestBody NewsService.Input in) {
    return news.update(actor, id, in);
  }

  @DeleteMapping("/api/news/{id}")
  Map<String, String> delete(Actor actor, @PathVariable long id) {
    news.delete(actor, id);
    return Map.of("status", "ok");
  }

  @PutMapping("/api/news/{id}/hidden")
  Map<String, String> hide(Actor actor, @PathVariable long id, @RequestBody FlagBody b) {
    news.setHidden(actor, id, Boolean.TRUE.equals(b.value()));
    return Map.of("status", "ok");
  }

  @GetMapping("/api/news/{id}/comments")
  List<CommentService.Comment> comments(Actor actor, @PathVariable long id) {
    return comments.list(actor, CommentService.Parent.POST, id);
  }

  @PostMapping("/api/news/{id}/comments")
  CommentService.Comment comment(Actor actor, @PathVariable long id, @RequestBody CommentBody b) {
    return comments.add(actor, CommentService.Parent.POST, id, b.body());
  }

  @DeleteMapping("/api/comments/{id}")
  Map<String, String> deleteComment(Actor actor, @PathVariable long id) {
    comments.delete(actor, id);
    return Map.of("status", "ok");
  }
}
