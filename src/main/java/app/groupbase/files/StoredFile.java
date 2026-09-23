package app.groupbase.files;

/** Метаданные зашифрованного файла. */
public record StoredFile(
    long id,
    String uuid,
    String name,
    String mime,
    long size,
    String sha256,
    Long uploadedBy,
    long createdAt) {}
