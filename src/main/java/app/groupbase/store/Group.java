package app.groupbase.store;

/**
 * @param sessionFrom первый день сессии (полночь по часовому поясу сайта), null — не задана
 * @param sessionTo последний день сессии
 * @param sessionNav кнопка «Сессия» в меню: auto — около сессии, show — всегда, hide — никогда
 */
public record Group(
    long id,
    String slug,
    String name,
    String university,
    Integer course,
    String avatar,
    long createdAt,
    Long archivedAt,
    Long sessionFrom,
    Long sessionTo,
    String sessionNav) {}
