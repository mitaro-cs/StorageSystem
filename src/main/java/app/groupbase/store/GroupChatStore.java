package app.groupbase.store;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Закреплённые чаты группы в Telegram. */
@Repository
public class GroupChatStore {

  public record Chat(long id, long groupId, String title, String url) {}

  private static final RowMapper<Chat> MAPPER =
      (rs, i) ->
          new Chat(
              rs.getLong("id"), rs.getLong("group_id"), rs.getString("title"), rs.getString("url"));

  private final JdbcClient db;

  public GroupChatStore(JdbcClient db) {
    this.db = db;
  }

  public List<Chat> list(long groupId) {
    return db.sql("SELECT * FROM group_chats WHERE group_id = ? ORDER BY position, id")
        .param(groupId)
        .query(MAPPER)
        .list();
  }

  public Map<Long, List<Chat>> byGroup(Collection<Long> groupIds) {
    Map<Long, List<Chat>> out = new HashMap<>();
    if (groupIds.isEmpty()) {
      return out;
    }
    db.sql("SELECT * FROM group_chats WHERE group_id IN (:g) ORDER BY position, id")
        .param("g", groupIds)
        .query(MAPPER)
        .list()
        .forEach(c -> out.computeIfAbsent(c.groupId(), k -> new java.util.ArrayList<>()).add(c));
    return out;
  }

  public Optional<Chat> find(long groupId, long id) {
    return db.sql("SELECT * FROM group_chats WHERE id = ? AND group_id = ?")
        .params(id, groupId)
        .query(MAPPER)
        .optional();
  }

  public long insert(long groupId, String title, String url, long by, long now) {
    return db.sql(
            "INSERT INTO group_chats (group_id, title, url, position, created_by, created_at)"
                + " VALUES (?, ?, ?, (SELECT coalesce(max(position), 0) + 1 FROM group_chats"
                + " WHERE group_id = ?), ?, ?) RETURNING id")
        .params(groupId, title, url, groupId, by, now)
        .query(Long.class)
        .single();
  }

  public void update(long id, String title, String url) {
    db.sql("UPDATE group_chats SET title = ?, url = ? WHERE id = ?")
        .params(title, url, id)
        .update();
  }

  public void delete(long id) {
    db.sql("DELETE FROM group_chats WHERE id = ?").param(id).update();
  }

  public int count(long groupId) {
    return db.sql("SELECT count(*) FROM group_chats WHERE group_id = ?")
        .param(groupId)
        .query(Integer.class)
        .single();
  }
}
