package app.groupbase.web;

import app.groupbase.auth.Permission;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Требуемое право. Область — группа из переменной пути {@code {groupId}}; если её нет, проверка
 * идёт на уровне инстанса. Эндпоинты с {@code {groupId}} без аннотации требуют {@code VIEW_GROUP}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Require {
  Permission value();
}
