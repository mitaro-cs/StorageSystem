package app.groupbase.web.api;

import app.groupbase.web.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Оформление человека, общее для всех его устройств (0.6): режим, дизайн, цвет, своя картинка фона
 * (приглушение и размытие) и значок. Хранится JSON-строкой в {@code users.appearance}; принимаем
 * только известные поля с допустимыми значениями — что угодно в базу не попадёт.
 */
final class Appearance {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Set<String> THEMES = Set.of("system", "light", "dark");
  private static final Set<String> STYLES = Set.of("plain", "glass", "depth", "neon", "paper");
  private static final Set<String> ICONS =
      Set.of("light", "dark", "ocean", "forest", "sunset", "grape");

  private Appearance() {}

  static Map<String, Object> parse(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Проверенная JSON-строка; неизвестные поля отбрасываются, неверные значения — 400. */
  static String clean(Map<String, Object> in) {
    Map<String, Object> out = new LinkedHashMap<>();
    oneOf(in, out, "theme", THEMES);
    oneOf(in, out, "style", STYLES);
    oneOf(in, out, "icon", ICONS);
    if (in.containsKey("hue")) {
      out.put("hue", in.get("hue") == null ? null : number(in, "hue", 0, 360));
    }
    if (in.containsKey("sat")) {
      out.put("sat", number(in, "sat", 0, 100));
    }
    if (in.containsKey("dim")) {
      out.put("dim", number(in, "dim", 0, 80));
    }
    if (in.containsKey("blur")) {
      // Размытие в px (0–40); прежние версии присылали true/false.
      if (in.get("blur") instanceof Boolean b) {
        out.put("blur", b ? 16 : 0);
      } else {
        out.put("blur", number(in, "blur", 0, 40));
      }
    }
    if (in.containsKey("at")) {
      out.put("at", (long) number(in, "at", 0, Long.MAX_VALUE));
    }
    return JSON.writeValueAsString(out);
  }

  private static void oneOf(
      Map<String, Object> in, Map<String, Object> out, String key, Set<String> allowed) {
    if (!in.containsKey(key)) {
      return;
    }
    if (!(in.get(key) instanceof String s) || !allowed.contains(s)) {
      throw bad(key);
    }
    out.put(key, s);
  }

  private static double number(Map<String, Object> in, String key, double min, double max) {
    if (!(in.get(key) instanceof Number n)
        || Double.isNaN(n.doubleValue())
        || n.doubleValue() < min
        || n.doubleValue() > max) {
      throw bad(key);
    }
    return n.doubleValue() == Math.rint(n.doubleValue()) ? n.longValue() : n.doubleValue();
  }

  private static ApiException bad(String key) {
    return ApiException.invalid("appearance." + key, "Неверное значение оформления");
  }
}
