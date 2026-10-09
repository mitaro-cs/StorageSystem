package app.groupbase.content;

import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;

/**
 * Порядок, как у людей (0.9.8): «Лабораторная 2» раньше «Лабораторной 10», регистр и «ё» не важны.
 * Числа внутри названия сравниваются как числа, остальное – по алфавиту (русский).
 */
public final class NaturalOrder implements Comparator<String> {

  public static final NaturalOrder INSTANCE = new NaturalOrder();

  private final Collator collator;

  private NaturalOrder() {
    collator = Collator.getInstance(Locale.forLanguageTag("ru"));
    collator.setStrength(Collator.PRIMARY);
  }

  @Override
  public int compare(String a, String b) {
    String x = a == null ? "" : a;
    String y = b == null ? "" : b;
    int i = 0;
    int j = 0;
    while (i < x.length() && j < y.length()) {
      boolean dx = Character.isDigit(x.charAt(i));
      boolean dy = Character.isDigit(y.charAt(j));
      int ei = end(x, i, dx);
      int ej = end(y, j, dy);
      String px = x.substring(i, ei);
      String py = y.substring(j, ej);
      int c;
      if (dx && dy) {
        String nx = px.replaceFirst("^0+(?=\\d)", "");
        String ny = py.replaceFirst("^0+(?=\\d)", "");
        c =
            nx.length() != ny.length()
                ? Integer.compare(nx.length(), ny.length())
                : nx.compareTo(ny);
      } else {
        c = collator.compare(px, py);
      }
      if (c != 0) {
        return c;
      }
      i = ei;
      j = ej;
    }
    return Integer.compare(x.length() - i, y.length() - j);
  }

  private static int end(String s, int from, boolean digits) {
    int k = from;
    while (k < s.length() && Character.isDigit(s.charAt(k)) == digits) {
      k++;
    }
    return k;
  }
}
