package app.groupbase.store;

/**
 * @param sessionFrom первый день сессии (полночь по часовому поясу сайта), null — не задана
 * @param sessionTo последний день сессии
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
    Long sessionTo) {}
