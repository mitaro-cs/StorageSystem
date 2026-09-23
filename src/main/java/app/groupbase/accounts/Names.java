package app.groupbase.accounts;

import app.groupbase.web.ApiException;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Проверка имён пользователей и отображаемых имён, транслитерация для автогенерации логинов. */
public final class Names {

  private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,31}");
  private static final Map<Character, String> TRANSLIT =
      Map.ofEntries(
          Map.entry('а', "a"),
          Map.entry('б', "b"),
          Map.entry('в', "v"),
          Map.entry('г', "g"),
          Map.entry('д', "d"),
          Map.entry('е', "e"),
          Map.entry('ё', "e"),
          Map.entry('ж', "zh"),
          Map.entry('з', "z"),
          Map.entry('и', "i"),
          Map.entry('й', "y"),
          Map.entry('к', "k"),
          Map.entry('л', "l"),
          Map.entry('м', "m"),
          Map.entry('н', "n"),
          Map.entry('о', "o"),
          Map.entry('п', "p"),
          Map.entry('р', "r"),
          Map.entry('с', "s"),
          Map.entry('т', "t"),
          Map.entry('у', "u"),
          Map.entry('ф', "f"),
          Map.entry('х', "kh"),
          Map.entry('ц', "ts"),
          Map.entry('ч', "ch"),
          Map.entry('ш', "sh"),
          Map.entry('щ', "shch"),
          Map.entry('ъ', ""),
          Map.entry('ы', "y"),
          Map.entry('ь', ""),
          Map.entry('э', "e"),
          Map.entry('ю', "yu"),
          Map.entry('я', "ya"));

  private Names() {}

  public static String username(String raw) {
    String u = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    if (!USERNAME.matcher(u).matches()) {
      throw ApiException.invalid(
          "username",
          "Имя пользователя: 3–32 символа, латиница, цифры, точка, дефис или подчёркивание");
    }
    if (u.startsWith("deleted-")) {
      throw ApiException.invalid("username", "Это имя зарезервировано");
    }
    return u;
  }

  public static String displayName(String raw) {
    String d = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
    if (d.isEmpty() || d.length() > 64) {
      throw ApiException.invalid("displayName", "Отображаемое имя: от 1 до 64 символов");
    }
    if (d.chars().anyMatch(Character::isISOControl)) {
      throw ApiException.invalid("displayName", "Недопустимые символы в имени");
    }
    return d;
  }

  /** «Иван Петров» → «ivan.petrov». Пустая строка, если из имени ничего не получилось. */
  public static String suggestUsername(String displayName) {
    StringBuilder out = new StringBuilder();
    String[] words = displayName.toLowerCase(Locale.ROOT).trim().split("\\s+");
    for (int w = 0; w < Math.min(words.length, 2); w++) {
      StringBuilder word = new StringBuilder();
      for (char c : words[w].toCharArray()) {
        if (TRANSLIT.containsKey(c)) {
          word.append(TRANSLIT.get(c));
        } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
          word.append(c);
        }
      }
      if (!word.isEmpty()) {
        if (!out.isEmpty()) {
          out.append('.');
        }
        out.append(word);
      }
    }
    String s = out.length() > 28 ? out.substring(0, 28) : out.toString();
    return s.length() < 3 ? "" : s;
  }

  public static String slug(String name) {
    String s = suggestUsername(name).replace('.', '-');
    if (s.isEmpty()) {
      s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
    return s.isEmpty() ? "group" : s;
  }
}
