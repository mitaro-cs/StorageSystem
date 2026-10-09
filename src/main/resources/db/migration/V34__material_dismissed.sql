-- «Скрыть у себя» (0.9.8): человек убирает материал из своего списка – у остальных он на месте.
CREATE TABLE material_dismissed (
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  material_id INTEGER NOT NULL REFERENCES materials(id) ON DELETE CASCADE,
  at          INTEGER NOT NULL,
  PRIMARY KEY (user_id, material_id)
);
CREATE INDEX material_dismissed_material ON material_dismissed(material_id);
