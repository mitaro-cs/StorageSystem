package app.groupbase.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SearchQueryTest {

  @Test
  void russianEndingsAreCutAndWordsArePrefixes() {
    assertThat(SearchQuery.fts("Задачи")).isEqualTo("\"задач\"*");
    assertThat(SearchQuery.fts("задача")).isEqualTo("\"задач\"*");
    assertThat(SearchQuery.fts("Лабораторной работы")).isEqualTo("\"лабораторн\"* \"работ\"*");
    assertThat(SearchQuery.fts("расчёт")).isEqualTo("\"расчет\"*");
    // Короткие и нерусские слова не трогаем.
    assertThat(SearchQuery.fts("ДЗ 12 GitHub")).isEqualTo("\"дз\"* \"12\"* \"github\"*");
    assertThat(SearchQuery.stem("ряды")).isEqualTo("ряд");
    assertThat(SearchQuery.stem("кот")).isEqualTo("кот");
  }

  @Test
  void ftsSyntaxFromInputIsIgnored() {
    assertThat(SearchQuery.fts("\"a\" OR b* NEAR(c)"))
        .isEqualTo("\"a\"* \"or\"* \"b\"* \"near\"* \"c\"*");
    assertThat(SearchQuery.fts("  *** ")).isEmpty();
    assertThat(SearchQuery.fts(null)).isEmpty();
    assertThat(SearchQuery.fts("а б в г д е ж з и к").split(" ")).hasSize(8);
  }

  @Test
  void highlightMarkersBecomeSegments() {
    assertThat(SearchService.segments("Типовой \u0002расчёт\u0003 №1"))
        .containsExactly(
            new SearchService.Segment("Типовой ", false),
            new SearchService.Segment("расчёт", true),
            new SearchService.Segment(" №1", false));
    assertThat(SearchService.segments("")).isEqualTo(List.of());
    assertThat(SearchService.plain("**жирный** `код` # заголовок"))
        .isEqualTo("жирный код заголовок");
  }

  @Test
  void originalLettersAreRestoredInHighlights() {
    var segs = SearchService.segments("Типовой \u0002расчет\u0003 №1");
    assertThat(SearchService.restore(segs, "Типовой расчёт №1"))
        .extracting(SearchService.Segment::text)
        .containsExactly("Типовой ", "расчёт", " №1");
    var snip = SearchService.segments("…еще \u0002Елки\u0003 и…");
    assertThat(SearchService.restore(snip, "Где ещё Ёлки и палки"))
        .extracting(SearchService.Segment::text)
        .containsExactly("…ещё ", "Ёлки", " и…");
  }
}
