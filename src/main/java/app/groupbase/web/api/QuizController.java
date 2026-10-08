package app.groupbase.web.api;

import app.groupbase.auth.Actor;
import app.groupbase.quiz.QuizService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Тесты онлайн по предмету: вести – MANAGE_SUBJECTS во всех группах предмета (проверка в {@link
 * QuizService}: группы берутся из предмета), проходить – всем, кто видит предмет.
 */
@RestController
class QuizController {

  private final QuizService quizzes;

  QuizController(QuizService quizzes) {
    this.quizzes = quizzes;
  }

  @GetMapping("/api/subjects/{id}/quizzes")
  List<QuizService.Item> list(Actor actor, @PathVariable long id) {
    return quizzes.list(actor, id);
  }

  @PostMapping("/api/subjects/{id}/quizzes")
  QuizService.Detail create(Actor actor, @PathVariable long id, @RequestBody QuizService.Input in) {
    return quizzes.create(actor, id, in);
  }

  @GetMapping("/api/quizzes/{id}")
  QuizService.Detail get(Actor actor, @PathVariable long id) {
    return quizzes.get(actor, id);
  }

  @PutMapping("/api/quizzes/{id}")
  QuizService.Detail update(Actor actor, @PathVariable long id, @RequestBody QuizService.Input in) {
    return quizzes.update(actor, id, in);
  }

  @DeleteMapping("/api/quizzes/{id}")
  Map<String, String> delete(Actor actor, @PathVariable long id) {
    quizzes.delete(actor, id);
    return Map.of("status", "ok");
  }

  @GetMapping("/api/quizzes/{id}/results")
  List<QuizService.Row> results(Actor actor, @PathVariable long id) {
    return quizzes.results(actor, id);
  }

  @PostMapping("/api/quizzes/{id}/attempts")
  QuizService.Attempt start(Actor actor, @PathVariable long id) {
    return quizzes.start(actor, id);
  }

  record Finish(List<QuizService.Answer> answers) {}

  @PostMapping("/api/quiz-attempts/{id}/finish")
  QuizService.Result finish(Actor actor, @PathVariable long id, @RequestBody Finish in) {
    return quizzes.finish(actor, id, in.answers());
  }

  @GetMapping("/api/quiz-attempts/{id}")
  QuizService.Result result(Actor actor, @PathVariable long id) {
    return quizzes.result(actor, id);
  }
}
