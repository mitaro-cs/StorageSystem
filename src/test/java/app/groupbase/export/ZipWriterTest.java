package app.groupbase.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class ZipWriterTest {

  @Test
  void namesAreSafeOnEveryOs() {
    assertThat(ZipWriter.safe("Задача: 1/2 *срочно*?")).isEqualTo("Задача_ 1_2 _срочно__");
    assertThat(ZipWriter.safe("  ..скрытый.  ")).isEqualTo("скрытый");
    assertThat(ZipWriter.safe("")).isEqualTo("без названия");
    assertThat(ZipWriter.safe("а".repeat(200))).hasSize(90);
    assertThat(ZipWriter.path("Предметы", "Физика/Механика", "Лекция.pdf"))
        .isEqualTo("Предметы/Физика_Механика/Лекция.pdf");
  }

  @Test
  void duplicatesGetNumbers() {
    ZipWriter z = new ZipWriter(new ByteArrayOutputStream());
    assertThat(z.unique("a/Лекция.pdf")).isEqualTo("a/Лекция.pdf");
    assertThat(z.unique("a/лекция.PDF")).isEqualTo("a/лекция (2).PDF");
    assertThat(z.unique("a/Лекция.pdf")).isEqualTo("a/Лекция (3).pdf");
    assertThat(z.unique("a/папка")).isEqualTo("a/папка");
    assertThat(z.unique("a/папка")).isEqualTo("a/папка (2)");
  }

  @Test
  void csvIsExcelFriendlyAndFormulaSafe() {
    String csv =
        ZipWriter.csv(List.of(List.of("ФИО", "Текст"), List.of("Ким; Олег", "=HYPERLINK(\"x\")")));
    assertThat(csv).startsWith("﻿ФИО;Текст\r\n");
    assertThat(csv).contains("\"Ким; Олег\";\"'=HYPERLINK(\"\"x\"\")\"");
    assertThat(ExportService.fileName("Лекция 3", "конспект.pdf")).isEqualTo("Лекция 3.pdf");
    assertThat(ExportService.fileName("Лекция 3.PDF", "конспект.pdf")).isEqualTo("Лекция 3.PDF");
    assertThat(ExportService.fileName("Ссылки", "readme")).isEqualTo("Ссылки");
  }
}
