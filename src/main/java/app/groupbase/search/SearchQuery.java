package app.groupbase.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Запрос пользователя → выражение FTS5. Каждое слово ищется по префиксу, у русских слов отрезается
 * окончание: «задачи» и «задача» дают «задач*». Кавычки и операторы FTS5 из ввода не проходят.
 */
public final class SearchQuery {

  private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
  private static final Pattern CYRILLIC = Pattern.compile("\\p{IsCyrillic}");
  private static final int MAX_TERMS = 8;

  // От длинных к коротким, чтобы «ами» отрезалось раньше «и».
  private static final String[] ENDINGS = {
    "иями", "ями", "ами", "ого", "его", "ому", "ему", "ыми", "ими", "ией", "иям", "иях", "ать",
    "ять", "ить", "еть", "ешь", "ете", "ует", "ют", "ут", "ая", "яя", "ое", "ее", "ые", "ие", "ый",
    "ий", "ой", "ей", "ам", "ям", "ах", "ях", "ом", "ем", "ов", "ев", "ия", "ью", "ы", "и", "а",
    "я", "о", "е", "у", "ю", "ь", "й"
  };

  private SearchQuery() {}

  /** Выражение для MATCH или пустая строка, если искать нечего. */
  public static String fts(String input) {
    if (input == null) {
      return "";
    }
    String q = input.toLowerCase(Locale.ROOT).replace('ё', 'е');
    List<String> terms = new ArrayList<>();
    Matcher m = WORD.matcher(q);
    while (m.find() && terms.size() < MAX_TERMS) {
      terms.add("\"" + stem(m.group()) + "\"*");
    }
    return String.join(" ", terms);
  }

  /** Основа слова: длинные окончания — если остаётся от 4 букв, однобуквенные — от 3 («ряды»). */
  static String stem(String word) {
    if (word.length() < 4 || !CYRILLIC.matcher(word).find()) {
      return word;
    }
    for (String e : ENDINGS) {
      int rest = word.length() - e.length();
      if (word.endsWith(e) && rest >= (e.length() == 1 ? 3 : 4)) {
        return word.substring(0, rest);
      }
    }
    return word;
  }
}
