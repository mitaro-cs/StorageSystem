package app.groupbase.web;

import org.springframework.http.HttpStatus;

/** Ошибка API с кодом для фронта и сообщением по-русски для человека. */
public class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;
  private final transient Object details;

  public ApiException(HttpStatus status, String code, String message) {
    this(status, code, message, null);
  }

  public ApiException(HttpStatus status, String code, String message, Object details) {
    super(message, null, false, false);
    this.status = status;
    this.code = code;
    this.details = details;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public Object details() {
    return details;
  }

  public static ApiException badRequest(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "bad_request", message);
  }

  public static ApiException invalid(String field, String message) {
    return new ApiException(
        HttpStatus.BAD_REQUEST, "invalid", message, java.util.Map.of("field", field));
  }

  public static ApiException unauthorized() {
    return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Нужно войти");
  }

  public static ApiException forbidden() {
    return new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Недостаточно прав");
  }

  public static ApiException forbidden(String message) {
    return new ApiException(HttpStatus.FORBIDDEN, "forbidden", message);
  }

  public static ApiException notFound() {
    return new ApiException(HttpStatus.NOT_FOUND, "not_found", "Не найдено");
  }

  public static ApiException conflict(String code, String message) {
    return new ApiException(HttpStatus.CONFLICT, code, message);
  }
}
