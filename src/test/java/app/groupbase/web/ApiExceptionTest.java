package app.groupbase.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ApiExceptionTest {

  @Test
  void factoriesGiveStatusCodeAndRussianMessage() {
    assertThat(ApiException.notFound().status()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(ApiException.forbidden().status()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(ApiException.unauthorized().status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    ApiException invalid = ApiException.invalid("name", "Название — до 60 символов");
    assertThat(invalid.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(invalid.getMessage()).isEqualTo("Название — до 60 символов");
    ApiException conflict = ApiException.conflict("last_admin", "Это последний администратор");
    assertThat(conflict.status()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(conflict.code()).isEqualTo("last_admin");
    assertThat(ApiException.forbidden("Нельзя").getMessage()).isEqualTo("Нельзя");
    assertThat(ApiException.badRequest("Плохо").status()).isEqualTo(HttpStatus.BAD_REQUEST);
  }
}
