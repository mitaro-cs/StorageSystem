package app.groupbase.schedule;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Название занятия из календаря → предмет, вид, преподаватель, аудитория. В выгрузках вузов всё
 * бывает в одной строке: «Математический анализ (Лекция)», «Лек. Физика, ауд. 214, Иванов И.И.»,
 * «[пр] Иностранный язык 1 подгр.». Предмет потом сопоставляется с предметами группы.
 */
final class Titles {

  record Parsed(String name, String key, LessonKind kind, String teacher, String place) {}

  /** Предмет группы для сопоставления. */
  record Subject(long id, String name) {}

  private static final int F =
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

  /**
   * Слова вида занятия — и полные, и сокращения; «лаб.» раньше «лек.», консультация и пересдача —
   * экзамена.
   */
  private static final List<Map.Entry<LessonKind, Pattern>> KINDS =
      List.of(
          Map.entry(
              LessonKind.LAB,
              Pattern.compile("\\b(?:лаб(?:ораторн\\w*)?(?:\\s+работ\\w*)?|лр|lab\\w*)\\b\\.?", F)),
          Map.entry(
              LessonKind.LECTURE,
              Pattern.compile(
                  "\\b(?:лек(?:ц\\w*)?(?:\\s+заняти\\w*)?|лк|lecture\\w*|lec)\\b\\.?", F)),
          Map.entry(
              LessonKind.SEMINAR,
              Pattern.compile("\\b(?:семинар\\w*(?:\\s+заняти\\w*)?|сем|seminar\\w*)\\b\\.?", F)),
          Map.entry(
              LessonKind.PRACTICE,
              Pattern.compile(
                  "\\b(?:практ\\w*(?:\\s+заняти\\w*)?|пр|пз|practice\\w*|practical)\\b\\.?", F)),
          Map.entry(
              LessonKind.CONSULT,
              Pattern.compile("\\b(?:консультац\\w*|конс|consultation)\\b\\.?", F)),
          Map.entry(LessonKind.RETAKE, Pattern.compile("\\b(?:пересда\\w*|retake\\w*)\\b", F)),
          Map.entry(LessonKind.EXAM, Pattern.compile("\\b(?:экзамен\\w*|экз|exam\\w*)\\b\\.?", F)),
          Map.entry(
              LessonKind.CREDIT,
              Pattern.compile("\\b(?:(?:диф\\w*\\.?\\s*)?зач[её]т\\w*|зач)\\b\\.?", F)));

  private static final Pattern PLACE =
      Pattern.compile(
          "\\b(?:ауд(?:итория|\\.)?|каб(?:инет|\\.)?)\\s*№?\\s*[\\p{L}\\d./-]*\\d[\\p{L}\\d./-]*",
          F);

  /** «Иванов И.И.», «Петрова-Водкина А. С.», «И.И. Иванов». */
  private static final Pattern TEACHER =
      Pattern.compile(
          "(\\p{Lu}\\p{Ll}+(?:-\\p{Lu}\\p{Ll}+)?)\\s+(\\p{Lu})\\.\\s*(\\p{Lu})\\.?"
              + "|(\\p{Lu})\\.\\s*(\\p{Lu})\\.\\s*(\\p{Lu}\\p{Ll}+(?:-\\p{Lu}\\p{Ll}+)?)");

  private static final Pattern TEACHER_LINE =
      Pattern.compile(
          "^\\s*(?:преподавател\\w*|преп\\.?|teacher|lecturer|instructor)\\s*[:：-]\\s*(.+)$",
          F | Pattern.MULTILINE);

  private static final Pattern PLACE_LINE =
      Pattern.compile(
          "^\\s*(?:аудитория|ауд\\.?|кабинет|место|room|location)\\s*[:：-]\\s*(.+)$",
          F | Pattern.MULTILINE);

  /** Ничего не говорят о предмете: «занятие», «пара», «работа». */
  private static final Pattern FILLER =
      Pattern.compile("\\b(?:занятие|занятия|пара|пары|работа|работы)\\b", F);

  /** Как в расписаниях сокращают предметы — в полные слова, прежде чем сравнивать. */
  private static final Map<String, String> SHORT =
      Map.ofEntries(
          Map.entry("физкультура", "физическая культура"),
          Map.entry("физра", "физическая культура"),
          Map.entry("физ ра", "физическая культура"),
          Map.entry("матан", "математический анализ"),
          Map.entry("вышмат", "высшая математика"),
          Map.entry("иняз", "иностранный язык"),
          Map.entry("ин яз", "иностранный язык"),
          Map.entry("бжд", "безопасность жизнедеятельности"),
          Map.entry("линал", "линейная алгебра"),
          Map.entry("тервер", "теория вероятностей"));

  /** При сравнении с предметами не считаются: «1 подгруппа» и «группа №1» — про номер. */
  private static final Set<String> NOISE = Set.of("группа", "подгруппа", "гр", "подгр", "и");

  private Titles() {}

  static Parsed parse(String summary, String description, String location, String categories) {
    String raw = summary == null ? "" : summary.strip();
    String name = raw;
    LessonKind kind = LessonKind.OTHER;
    for (var e : KINDS) {
      Matcher m = e.getValue().matcher(name);
      if (m.find()) {
        kind = e.getKey();
        name = m.replaceAll(" ");
        break;
      }
    }
    if (kind == LessonKind.OTHER) {
      kind = kindIn(categories);
    }
    if (kind == LessonKind.OTHER) {
      kind = kindIn(description);
    }
    String placeInTitle = "";
    Matcher pm = PLACE.matcher(name);
    if (pm.find()) {
      placeInTitle = pm.group().strip();
      name = pm.replaceAll(" ");
    }
    String teacherInTitle = "";
    Matcher tm = TEACHER.matcher(name);
    if (tm.find()) {
      teacherInTitle =
          tm.group(1) != null
              ? tm.group(1) + " " + tm.group(2) + ". " + tm.group(3) + "."
              : tm.group(6) + " " + tm.group(4) + ". " + tm.group(5) + ".";
      name = name.substring(0, tm.start()) + " " + name.substring(tm.end());
    }
    name = tidy(FILLER.matcher(name).replaceAll(" "));
    if (name.isEmpty()) {
      name = tidy(raw);
    }
    if (name.isEmpty()) {
      name = "Занятие";
    }
    name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
    if (name.length() > 200) {
      name = name.substring(0, 200).strip();
    }

    String teacher = line(TEACHER_LINE, description);
    if (teacher.isEmpty()) {
      teacher = teacherInTitle;
    }
    String place = location == null ? "" : location.strip().replaceAll("\\s+", " ");
    if (place.isEmpty()) {
      place = placeInTitle.isEmpty() ? line(PLACE_LINE, description) : placeInTitle;
    }
    return new Parsed(name, key(name), kind, clip(teacher, 120), clip(place, 80));
  }

  private static LessonKind kindIn(String text) {
    if (text == null || text.isBlank()) {
      return LessonKind.OTHER;
    }
    for (var e : KINDS) {
      if (e.getValue().matcher(text).find()) {
        return e.getKey();
      }
    }
    return LessonKind.OTHER;
  }

  private static String line(Pattern p, String text) {
    if (text == null) {
      return "";
    }
    Matcher m = p.matcher(text);
    return m.find() ? m.group(1).strip() : "";
  }

  private static String clip(String s, int max) {
    return s.length() > max ? s.substring(0, max).strip() : s;
  }

  /** Убрать пустые скобки, разделители и знаки по краям, лишние пробелы. */
  static String tidy(String s) {
    String t = s;
    for (int i = 0; i < 3; i++) {
      t = t.replaceAll("[(\\[{]\\s*[)\\]}]", " ");
    }
    t = t.replaceAll("\\s+", " ").strip();
    t = t.replaceAll("^[\\s,.;:|/\\\\––-]+|[\\s,;:|/\\\\––-]+$", "").strip();
    // Непарные скобки по краям после вырезания вида занятия: «(Лекция) Физика» → «Физика».
    t = t.replaceAll("^[)\\]}]+|[(\\[{]+$", "").strip();
    return t.replaceAll("\\s+", " ");
  }

  /** Ключ для группировки одинаковых названий: без регистра, «ё», знаков и лишних пробелов. */
  static String key(String name) {
    return name.toLowerCase(Locale.ROOT)
        .replace('ё', 'е')
        .replaceAll("№\\s*", "№")
        .replaceAll("[^\\p{L}\\p{N}№]+", " ")
        .strip();
  }

  /**
   * Предмет группы для названия: точное совпадение, по началу слов («Мат. анализ» — «Математический
   * анализ») или по первым буквам («ОП» — «Основы программирования»). Номера подгрупп должны
   * совпасть. Если подходят два предмета одинаково — не угадываем (null).
   */
  static Long match(String name, List<Subject> subjects) {
    double best = 0;
    double second = 0;
    Long id = null;
    for (Subject s : subjects) {
      double score = score(name, s.name());
      if (score > best) {
        second = best;
        best = score;
        id = s.id();
      } else if (score > second) {
        second = score;
      }
    }
    return best >= 0.6 && best - second >= 0.1 ? id : null;
  }

  static double score(String a, String b) {
    String ka = expand(key(a));
    String kb = expand(key(b));
    if (ka.isEmpty() || kb.isEmpty()) {
      return 0;
    }
    if (ka.equals(kb)) {
      return 1;
    }
    List<String> na = numbers(ka);
    List<String> nb = numbers(kb);
    if (!na.isEmpty() && !nb.isEmpty() && !na.equals(nb)) {
      return 0; // «№1» и «№2» — разные подгруппы
    }
    double penalty = na.equals(nb) ? 1 : 0.85;
    List<String> wa = words(ka);
    List<String> wb = words(kb);
    if (wa.isEmpty() || wb.isEmpty()) {
      return 0;
    }
    double acronym =
        (wa.size() == 1 && initials(wb).contains(wa.getFirst()))
                || (wb.size() == 1 && initials(wa).contains(wb.getFirst()))
            ? 0.9
            : 0;
    int matched = covered(wa, wb) + covered(wb, wa);
    double dice = (double) matched / (wa.size() + wb.size());
    return Math.max(dice * penalty, acronym * penalty);
  }

  /** «физкультура 1 подгр» → «физическая культура 1 подгр». */
  private static String expand(String key) {
    String out = " " + key + " ";
    for (var e : SHORT.entrySet()) {
      out = out.replace(" " + e.getKey() + " ", " " + e.getValue() + " ");
    }
    return out.strip();
  }

  private static List<String> numbers(String key) {
    List<String> out = new ArrayList<>();
    Matcher m = Pattern.compile("\\d+").matcher(key);
    while (m.find()) {
      out.add(m.group());
    }
    return out;
  }

  private static List<String> words(String key) {
    List<String> out = new ArrayList<>();
    for (String w : key.split(" ")) {
      String t = w.replace("№", "");
      if (t.length() >= 2 && !t.chars().allMatch(Character::isDigit) && !NOISE.contains(t)) {
        out.add(t);
      }
    }
    return out;
  }

  /** Первые буквы слов: «основы программирования» → «оп». */
  private static Set<String> initials(List<String> words) {
    StringBuilder all = new StringBuilder();
    for (String w : words) {
      all.append(w.charAt(0));
    }
    return Set.of(all.toString());
  }

  /** Сколько слов из a нашлось в b. */
  private static int covered(List<String> a, List<String> b) {
    int n = 0;
    for (String x : a) {
      for (String y : b) {
        if (similar(x, y)) {
          n++;
          break;
        }
      }
    }
    return n;
  }

  /**
   * Одно слово: целиком, началом («мат» — «математический», не короче трёх букв) или с другим
   * окончанием («программирование» — «программирования»), но «физика» — не «физическая».
   */
  static boolean similar(String x, String y) {
    if (x.equals(y)) {
      return true;
    }
    int m = Math.min(x.length(), y.length());
    if (m >= 3 && (x.startsWith(y) || y.startsWith(x))) {
      return true;
    }
    int common = 0;
    while (common < m && x.charAt(common) == y.charAt(common)) {
      common++;
    }
    return (m >= 7 && common >= m - 2) || (m >= 5 && common >= m - 1);
  }
}
