package app.groupbase.content;

import app.groupbase.web.ApiException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Ссылки на чаты и каналы Telegram. Принимаем то, что люди копируют: {@code https://t.me/…}, {@code
 * t.me/…}, {@code @имя}, приглашения {@code t.me/+…} и {@code tg://}; храним одним видом — {@code
 * https://t.me/…}. Другие адреса не принимаются: кнопка обещает Telegram.
 */
public final class Telegram {

  private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{3,31}");
  private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_+/-]{2,150}");

  private Telegram() {}

  /** Ссылка вида https://t.me/…; пустая строка — пусто; иначе ошибка с понятным текстом. */
  public static String normalize(String raw, String field) {
    String s = raw == null ? "" : raw.strip();
    if (s.isEmpty()) {
      return "";
    }
    String path;
    String lower = s.toLowerCase(Locale.ROOT);
    if (s.startsWith("@") && NAME.matcher(s.substring(1)).matches()) {
      path = s.substring(1);
    } else if (lower.startsWith("tg://resolve?domain=")) {
      path = s.substring("tg://resolve?domain=".length());
    } else if (lower.startsWith("tg://join?invite=")) {
      path = "+" + s.substring("tg://join?invite=".length());
    } else {
      String rest = s.replaceFirst("(?i)^https?://", "");
      String host = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
      if (!host.equalsIgnoreCase("t.me")
          && !host.equalsIgnoreCase("telegram.me")
          && !host.equalsIgnoreCase("www.t.me")) {
        throw ApiException.invalid(field, "Нужна ссылка на Telegram: t.me/… или @имя");
      }
      path = rest.substring(host.length()).replaceFirst("^/", "");
    }
    // Хвосты вроде ?utm=… и / в конце не нужны.
    path = path.replaceFirst("[?#].*$", "").replaceFirst("/+$", "");
    if (!PATH.matcher(path).matches()) {
      throw ApiException.invalid(field, "Не похоже на ссылку на чат Telegram");
    }
    return "https://t.me/" + path;
  }
}
