package app.groupbase.store;

public record Group(
    long id,
    String slug,
    String name,
    String university,
    Integer course,
    String avatar,
    long createdAt,
    Long archivedAt) {}
