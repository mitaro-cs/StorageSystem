package app.groupbase.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Права. Уровень инстанса ({@link #instanceLevel}) проверяется без группы, остальные — в контексте
 * конкретной группы.
 */
public enum Permission {
  /** Настройки инстанса и групп, бэкапы, развёртывание. */
  MANAGE_INSTANCE(true),
  ASSIGN_MODERATOR(true),
  ASSIGN_HEADMAN(false),
  ASSIGN_DEPUTY(false),
  CREATE_ACCOUNTS(false),
  CREATE_INVITES(false),
  BLOCK_USERS(false),
  /** Выдать одноразовую ссылку сброса пароля участнику. */
  RESET_PASSWORDS(false),
  MANAGE_SUBJECTS(false),
  /** Связать предмет с другой группой; без этого права во второй группе — только запросом. */
  SHARE_SUBJECTS(false),
  PUBLISH_NEWS(false),
  PUBLISH_HOMEWORK(false),
  /** Загрузка материалов сразу в публикацию. */
  UPLOAD_MATERIALS(false),
  /** Загрузка материалов на премодерацию. */
  SUGGEST_MATERIALS(false),
  /** Удалять и скрывать чужой контент, одобрять материалы. */
  MODERATE_CONTENT(false),
  COMMENT(false),
  VIEW_AUDIT(false),
  /** Включать и выключать настраиваемые права (⚙) в своей группе. */
  MANAGE_PERMISSIONS(false),
  /** Видеть группу и её контент. */
  VIEW_GROUP(false);

  public final boolean instanceLevel;

  Permission(boolean instanceLevel) {
    this.instanceLevel = instanceLevel;
  }

  @JsonValue
  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  @JsonCreator
  public static Permission of(String id) {
    return valueOf(id.toUpperCase(Locale.ROOT));
  }
}
