package app.groupbase.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Все ошибки API в одном формате: {@code {"error": "код", "message": "..."}}. */
@RestControllerAdvice
class ApiErrorHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

  static Map<String, Object> body(String code, String message, Object details) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("error", code);
    m.put("message", message);
    if (details != null) {
      m.put("details", details);
    }
    return m;
  }

  /**
   * Поток событий ({@code /api/live}) не умеет JSON: ошибку отдаём одним кодом, без тела. Иначе
   * Spring не находит, чем записать ответ, и вместо 401 уходит 500 (EventSource после выхода
   * переподключается без cookie).
   */
  private static boolean eventStream(HttpServletRequest req) {
    String accept = req.getHeader("Accept");
    return accept != null && accept.contains("text/event-stream");
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<Map<String, Object>> api(ApiException e, HttpServletRequest req) {
    if (eventStream(req)) {
      return ResponseEntity.status(e.status()).build();
    }
    return ResponseEntity.status(e.status()).body(body(e.code(), e.getMessage(), e.details()));
  }

  /** Клиент ушёл посреди потока событий — отвечать некому, это не ошибка сервера. */
  @ExceptionHandler(AsyncRequestNotUsableException.class)
  void gone(AsyncRequestNotUsableException e) {
    log.debug("Клиент отключился: {}", e.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e) {
    var fe = e.getBindingResult().getFieldError();
    String field = fe == null ? null : fe.getField();
    String msg = fe == null ? "Некорректные данные" : fe.getDefaultMessage();
    return ResponseEntity.badRequest()
        .body(body("invalid", msg, field == null ? null : Map.of("field", field)));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class,
    MultipartException.class
  })
  ResponseEntity<Map<String, Object>> badRequest(Exception e) {
    if (e instanceof MaxUploadSizeExceededException) {
      return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
          .body(body("too_large", "Файл слишком большой", null));
    }
    log.debug("Некорректный запрос: {}", e.getMessage());
    return ResponseEntity.badRequest().body(body("bad_request", "Некорректный запрос", null));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<Map<String, Object>> notFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("not_found", "Не найдено", null));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<Map<String, Object>> method() {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .body(body("method_not_allowed", "Метод не поддерживается", null));
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<Map<String, Object>> mediaType() {
    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        .body(body("unsupported_media_type", "Неподдерживаемый формат запроса", null));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest req) {
    log.error("Необработанная ошибка: {}", e.getClass().getName(), e);
    if (eventStream(req)) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(body("internal", "Внутренняя ошибка сервера", null));
  }
}
