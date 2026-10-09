package app.groupbase.schedule;

import app.groupbase.web.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Вид занятия; в базе и API — {@link #id()}. */
public enum LessonKind {
  LECTURE,
  PRACTICE,
  SEMINAR,
  LAB,
  CONSULT,
  CREDIT,
  EXAM,
  RETAKE,
  OTHER;

  @JsonValue
  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static LessonKind of(String id) {
    for (LessonKind k : values()) {
      if (k.id().equals(id)) {
        return k;
      }
    }
    throw ApiException.invalid(
        "kind",
        "Вид занятия: лекция, практика, семинар, лабораторная, консультация, зачёт, экзамен,"
            + " пересдача или другое");
  }
}
