-- Два компьютера хоста напрямую (0.8): сопряжённые компьютеры сайта. Ключ каждого лежит открытым
-- только на нём самом (hosts.properties), здесь — его хеш. Таблица едет в копиях базы, поэтому
-- любой из компьютеров может стать главным и проверять ключ другого.
CREATE TABLE host_peers (
  computer_id TEXT    PRIMARY KEY,
  name        TEXT    NOT NULL,
  token_hash  BLOB    NOT NULL,
  created_at  INTEGER NOT NULL
) STRICT;
