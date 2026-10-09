package app.groupbase.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NaturalOrderTest {

  @Test
  void numbersInsideTitlesCompareAsNumbers() {
    List<String> list =
        new ArrayList<>(
            List.of(
                "Лабораторная работа 10.pdf",
                "Лабораторная работа 2.pdf",
                "лабораторная работа 1,2026.pdf",
                "Билеты",
                "Лабораторная работа 9.pdf"));
    list.sort(NaturalOrder.INSTANCE);
    assertThat(list)
        .containsExactly(
            "Билеты",
            "лабораторная работа 1,2026.pdf",
            "Лабораторная работа 2.pdf",
            "Лабораторная работа 9.pdf",
            "Лабораторная работа 10.pdf");
  }
}
