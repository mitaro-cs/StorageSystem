package app.groupbase.search;

import app.groupbase.auth.Actor;
import app.groupbase.auth.Permission;
import app.groupbase.content.Access;
import app.groupbase.content.NewsService.SubjectRef;
import app.groupbase.content.Targets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Поиск по новостям, заданиям, материалам и предметам. Видимость — те же правила, что у списков:
 * только группы пользователя, скрытое — автору и модераторам, материалы на проверке — автору и
 * модераторам.
 */
@Service
public class SearchService {

  /** Кусок текста; {@code hit} — совпадение с запросом (фронт выделяет без HTML). */
  public record Segment(String text, boolean hit) {}

  public record Hit(
      String kind,
      long id,
      List<Segment> title,
      List<Segment> snippet,
      SubjectRef subject,
      Long date,
      String url,
      boolean hidden) {}

  public record Result(String query, List<Hit> items, Map<String, Integer> counts) {}

  static final int PER_KIND = 20;
  private static final char OPEN = '\u0002';
  private static final char CLOSE = '\u0003';

  private final JdbcClient db;
  private final Access access;

  public SearchService(JdbcClient db, Access access) {
    this.db = db;
    this.access = access;
  }

  public Result search(Actor actor, String input, Long group, String onlyKind) {
    String match = SearchQuery.fts(input);
    String q = input == null ? "" : input.strip();
    List<Long> scope = access.scope(actor, group);
    Map<String, Integer> counts = new LinkedHashMap<>();
    if (match.isEmpty() || scope.isEmpty()) {
      return new Result(q, List.of(), counts);
    }
    Set<Long> mod = access.groupsWith(actor, Permission.MODERATE_CONTENT, scope);
    Map<String, Object> p = new HashMap<>();
    p.put("q", match);
    p.put("g", scope);
    p.put("mod", Targets.nonEmpty(mod));
    p.put("uid", actor.id());
    p.put("limit", PER_KIND);

    List<Ranked> all = new ArrayList<>();
    for (Kind k : Kind.values()) {
      if (onlyKind != null && !onlyKind.equals(k.id)) {
        continue;
      }
      List<Ranked> found = db.sql(k.sql).paramSource(p).query(this::ranked).list();
      counts.put(k.id, found.size());
      all.addAll(found);
    }
    all.sort(Comparator.comparingDouble(Ranked::rank));
    return new Result(q, all.stream().map(Ranked::hit).toList(), counts);
  }

  private record Ranked(Hit hit, double rank) {}

  private Ranked ranked(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
    String kind = rs.getString("kind");
    long id = rs.getLong("id");
    long sid = rs.getLong("subject_id");
    SubjectRef subject =
        rs.wasNull() ? null : new SubjectRef(sid, rs.getString("s_name"), rs.getString("s_color"));
    long date = rs.getLong("date");
    Long d = rs.wasNull() ? null : date;
    String url =
        switch (kind) {
          case "news" -> "/news/" + id;
          case "homework" -> "/homework/" + id;
          case "material" -> "/materials/" + id;
          default -> "/subjects/" + id;
        };
    Hit hit =
        new Hit(
            kind,
            id,
            restore(segments(rs.getString("title_hl")), rs.getString("orig_title")),
            plain(restore(segments(rs.getString("snip")), rs.getString("orig_body"))),
            subject,
            d,
            url,
            rs.getInt("hidden") == 1);
    return new Ranked(hit, rs.getDouble("rank"));
  }

  /** Убирает разметку Markdown из фрагмента, чтобы в выдаче не было звёздочек и решёток. */
  static String plain(String s) {
    return s == null ? "" : s.replaceAll("[*_`#>|~]+", "").replaceAll("\\s+", " ");
  }

  static List<Segment> plain(List<Segment> segs) {
    List<Segment> out = new ArrayList<>();
    for (Segment seg : segs) {
      String t = plain(seg.text());
      if (!t.isEmpty()) {
        out.add(new Segment(t, seg.hit()));
      }
    }
    return out;
  }

  static String fold(String s) {
    return s.replace('ё', 'е').replace('Ё', 'Е');
  }

  /**
   * В индексе «ё» заменена на «е»; замена не меняет длину, поэтому буквы исходного текста можно
   * вернуть по тем же позициям. Фрагмент ищется в тексте по содержимому (без «…» по краям).
   */
  static List<Segment> restore(List<Segment> segs, String original) {
    if (original == null || segs.isEmpty()) {
      return segs;
    }
    StringBuilder all = new StringBuilder();
    segs.forEach(x -> all.append(x.text()));
    String core = all.toString();
    int lead = 0;
    if (core.startsWith("…")) {
      lead = 1;
      core = core.substring(1);
    }
    if (core.endsWith("…")) {
      core = core.substring(0, core.length() - 1);
    }
    int at = fold(original).indexOf(core);
    if (at < 0 || core.isEmpty()) {
      return segs;
    }
    List<Segment> out = new ArrayList<>();
    int pos = 0;
    for (Segment seg : segs) {
      StringBuilder b = new StringBuilder();
      for (char c : seg.text().toCharArray()) {
        int i = pos - lead;
        b.append(i >= 0 && i < core.length() ? original.charAt(at + i) : c);
        pos++;
      }
      out.add(new Segment(b.toString(), seg.hit()));
    }
    return out;
  }

  static List<Segment> segments(String marked) {
    List<Segment> out = new ArrayList<>();
    if (marked == null || marked.isEmpty()) {
      return out;
    }
    StringBuilder cur = new StringBuilder();
    boolean hit = false;
    for (char c : marked.toCharArray()) {
      if (c == OPEN || c == CLOSE) {
        if (!cur.isEmpty()) {
          out.add(new Segment(cur.toString(), hit));
          cur.setLength(0);
        }
        hit = c == OPEN;
      } else {
        cur.append(c);
      }
    }
    if (!cur.isEmpty()) {
      out.add(new Segment(cur.toString(), hit));
    }
    return out;
  }

  private static final String MARKS = "char(2), char(3)";

  private static String select(String kind, String idExpr) {
    return "SELECT '"
        + kind
        + "' AS kind, "
        + idExpr
        + " AS id, highlight(search_index, 0, "
        + MARKS
        + ") AS title_hl, snippet(search_index, 1, "
        + MARKS
        + ", '…', 14) AS snip, bm25(search_index, 4.0, 1.0) AS rank, ";
  }

  private enum Kind {
    HOMEWORK(
        "homework",
        select("homework", "h.id")
            + """
            h.subject_id, s.name AS s_name, s.color AS s_color, h.due_at AS date, h.hidden,
              h.title AS orig_title, h.body_md AS orig_body
            FROM search_index JOIN homework h ON search_index.rowid = h.id * 4 + 1
            JOIN subjects s ON s.id = h.subject_id
            WHERE search_index MATCH :q
              AND EXISTS (SELECT 1 FROM homework_targets t WHERE t.homework_id = h.id AND t.group_id IN (:g))
              AND (h.hidden = 0 OR h.author_id = :uid OR EXISTS (
                SELECT 1 FROM homework_targets t2 WHERE t2.homework_id = h.id AND t2.group_id IN (:mod)))
            ORDER BY rank LIMIT :limit
            """),
    NEWS(
        "news",
        select("news", "p.id")
            + """
            p.subject_id, s.name AS s_name, s.color AS s_color, p.created_at AS date, p.hidden,
              p.title AS orig_title, p.body_md AS orig_body
            FROM search_index JOIN posts p ON search_index.rowid = p.id * 4
            LEFT JOIN subjects s ON s.id = p.subject_id
            WHERE search_index MATCH :q
              AND EXISTS (SELECT 1 FROM post_targets t WHERE t.post_id = p.id AND t.group_id IN (:g))
              AND (p.hidden = 0 OR p.author_id = :uid OR EXISTS (
                SELECT 1 FROM post_targets t2 WHERE t2.post_id = p.id AND t2.group_id IN (:mod)))
            ORDER BY rank LIMIT :limit
            """),
    MATERIAL(
        "material",
        select("material", "m.id")
            + """
            m.subject_id, s.name AS s_name, s.color AS s_color, m.created_at AS date,
              (m.hidden = 1 OR m.status = 'pending') AS hidden, m.title AS orig_title,
              m.description || ' ' || ifnull(m.url, '') || ' ' || ifnull(f.name, '') AS orig_body
            FROM search_index JOIN materials m ON search_index.rowid = m.id * 4 + 2
            JOIN subjects s ON s.id = m.subject_id
            LEFT JOIN files f ON f.id = m.file_id
            WHERE search_index MATCH :q
              AND m.status != 'rejected'
              AND EXISTS (SELECT 1 FROM subject_groups sg WHERE sg.subject_id = m.subject_id AND sg.group_id IN (:g))
              AND ((m.status = 'published' AND m.hidden = 0) OR m.author_id = :uid OR EXISTS (
                SELECT 1 FROM subject_groups sg2 WHERE sg2.subject_id = m.subject_id AND sg2.group_id IN (:mod)))
            ORDER BY rank LIMIT :limit
            """),
    SUBJECT(
        "subject",
        select("subject", "s.id")
            + """
            s.id AS subject_id, s.name AS s_name, s.color AS s_color, NULL AS date,
              (s.archived_at IS NOT NULL) AS hidden, s.name AS orig_title, s.teacher AS orig_body
            FROM search_index JOIN subjects s ON search_index.rowid = s.id * 4 + 3
            WHERE search_index MATCH :q
              AND EXISTS (SELECT 1 FROM subject_groups sg WHERE sg.subject_id = s.id AND sg.group_id IN (:g))
            ORDER BY rank LIMIT :limit
            """);

    final String id;
    final String sql;

    Kind(String id, String sql) {
      this.id = id;
      this.sql = sql;
    }
  }
}
