package app.groupbase.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SubgroupTitleTest {

  @Test
  void findsSubgroupNumberInScheduleTitle() {
    assertThat(SubjectService.subgroupOf("Иностранный язык (2 подгр.)")).isEqualTo(2);
    assertThat(SubjectService.subgroupOf("Английский язык 1 гр.")).isEqualTo(1);
    assertThat(SubjectService.subgroupOf("Физкультура, подгруппа 3")).isEqualTo(3);
    assertThat(SubjectService.subgroupOf("Английский №2")).isEqualTo(2);
    assertThat(SubjectService.subgroupOf("Английский (2)")).isEqualTo(2);
    assertThat(SubjectService.subgroupOf("Высшая математика")).isZero();
    assertThat(SubjectService.subgroupOf(null)).isZero();
  }
}
