package app.groupbase.files;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

/**
 * Конспект в Markdown → документ Word (.docx) без внешних библиотек: commonmark разбирает текст, а
 * WordprocessingML пишется вручную. Заголовки, абзацы, жирный/курсив/зачёркнутый, код, списки
 * (вложенные), цитаты, таблицы, ссылки, разделители. Картинки — подписью: файлы в документ не
 * встраиваем.
 */
public final class MarkdownDocx {

  private static final List<Extension> EXTENSIONS =
      List.of(TablesExtension.create(), StrikethroughExtension.create());
  private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();

  private final StringBuilder body = new StringBuilder();
  private final List<String> links = new ArrayList<>();

  private MarkdownDocx() {}

  /** Записать .docx из текста Markdown. */
  public static void write(String markdown, String title, OutputStream out) throws IOException {
    MarkdownDocx d = new MarkdownDocx();
    d.blocks(PARSER.parse(markdown == null ? "" : markdown), 0);
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      put(zip, "[Content_Types].xml", CONTENT_TYPES);
      put(zip, "_rels/.rels", ROOT_RELS);
      put(zip, "docProps/core.xml", core(title));
      put(zip, "word/styles.xml", STYLES);
      put(zip, "word/_rels/document.xml.rels", d.rels());
      put(
          zip,
          "word/document.xml",
          "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
              + "<w:document xmlns:w=\""
              + W
              + "\" xmlns:r=\""
              + R
              + "\"><w:body>"
              + d.body
              + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\""
              + " w:right=\"850\" w:bottom=\"1134\" w:left=\"1701\" w:header=\"708\""
              + " w:footer=\"708\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>");
    }
  }

  // ---------- блоки ----------

  private void blocks(Node parent, int depth) {
    for (Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
      block(n, depth);
    }
  }

  private void block(Node n, int depth) {
    switch (n) {
      case Heading h -> paragraph("Heading" + Math.min(h.getLevel(), 4), 0, null, h);
      case Paragraph p -> paragraph("Normal", depth, null, p);
      case BulletList b -> {
        for (Node item = b.getFirstChild(); item != null; item = item.getNext()) {
          listItem((ListItem) item, depth + 1, "•");
        }
      }
      case OrderedList o -> {
        Integer start = o.getMarkerStartNumber();
        int i = start == null ? 1 : start;
        for (Node item = o.getFirstChild(); item != null; item = item.getNext()) {
          listItem((ListItem) item, depth + 1, (i++) + ".");
        }
      }
      case FencedCodeBlock c -> code(c.getLiteral(), depth);
      case IndentedCodeBlock c -> code(c.getLiteral(), depth);
      case BlockQuote q -> {
        for (Node c = q.getFirstChild(); c != null; c = c.getNext()) {
          if (c instanceof Paragraph p) {
            paragraph("Quote", depth, null, p);
          } else {
            block(c, depth);
          }
        }
      }
      case ThematicBreak unused ->
          body.append(
              "<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\""
                  + " w:color=\"BFBFBF\"/></w:pBdr></w:pPr></w:p>");
      case TableBlock t -> table(t);
      case HtmlBlock h -> code(h.getLiteral(), depth);
      default -> blocks(n, depth);
    }
  }

  /** Пункт списка: маркер или номер в начале первого абзаца, вложенное — глубже. */
  private void listItem(ListItem item, int depth, String marker) {
    boolean first = true;
    for (Node c = item.getFirstChild(); c != null; c = c.getNext()) {
      if (c instanceof Paragraph p) {
        paragraph("ListParagraph", depth, first ? marker : null, p);
        first = false;
      } else {
        block(c, depth);
      }
    }
    if (first) {
      // пустой пункт
      body.append("<w:p>").append(pPr("ListParagraph", depth, true)).append(run(marker, F_NONE));
      body.append("</w:p>");
    }
  }

  private void paragraph(String style, int depth, String marker, Node inline) {
    body.append("<w:p>").append(pPr(style, depth, marker != null));
    if (marker != null) {
      body.append(run(marker + "\t", F_NONE));
    }
    inlines(inline, F_NONE, null);
    body.append("</w:p>");
  }

  private static String pPr(String style, int depth, boolean hanging) {
    StringBuilder s = new StringBuilder("<w:pPr><w:pStyle w:val=\"").append(style).append("\"/>");
    if (depth > 0) {
      int left = 360 * depth;
      s.append("<w:tabs><w:tab w:val=\"left\" w:pos=\"").append(left).append("\"/></w:tabs>");
      s.append("<w:ind w:left=\"").append(left).append('"');
      if (hanging) {
        s.append(" w:hanging=\"360\"");
      }
      s.append("/>");
    }
    return s.append("</w:pPr>").toString();
  }

  private void code(String literal, int depth) {
    String text = literal == null ? "" : literal.stripTrailing();
    for (String line : text.split("\n", -1)) {
      body.append("<w:p>").append(pPr("Code", depth, false)).append(run(line, F_CODE));
      body.append("</w:p>");
    }
  }

  private void table(TableBlock t) {
    body.append(
        "<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"5000\""
            + " w:type=\"pct\"/></w:tblPr>");
    for (Node section = t.getFirstChild(); section != null; section = section.getNext()) {
      boolean head = section instanceof TableHead;
      for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
        if (!(row instanceof TableRow)) {
          continue;
        }
        body.append("<w:tr>");
        for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
          if (!(cell instanceof TableCell)) {
            continue;
          }
          body.append("<w:tc><w:tcPr><w:tcW w:w=\"0\" w:type=\"auto\"/>");
          if (head) {
            body.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F2F2F2\"/>");
          }
          body.append("</w:tcPr><w:p>");
          inlines(cell, head ? F_BOLD : F_NONE, null);
          body.append("</w:p></w:tc>");
        }
        body.append("</w:tr>");
      }
    }
    body.append("</w:tbl><w:p/>");
  }

  // ---------- строки ----------

  private static final int F_NONE = 0;
  private static final int F_BOLD = 1;
  private static final int F_ITALIC = 2;
  private static final int F_STRIKE = 4;
  private static final int F_CODE = 8;
  private static final int F_LINK = 16;

  private void inlines(Node parent, int flags, String link) {
    for (Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
      switch (n) {
        case Text t -> body.append(run(t.getLiteral(), flags));
        case Code c -> body.append(run(c.getLiteral(), flags | F_CODE));
        case Emphasis e -> inlines(e, flags | F_ITALIC, link);
        case StrongEmphasis e -> inlines(e, flags | F_BOLD, link);
        case Strikethrough e -> inlines(e, flags | F_STRIKE, link);
        case SoftLineBreak s -> body.append(run(" ", flags));
        case HardLineBreak h -> body.append("<w:r><w:br/></w:r>");
        case HtmlInline h -> body.append(run(h.getLiteral(), flags));
        case Image i -> {
          String alt = i.getFirstChild() instanceof Text t ? t.getLiteral() : "картинка";
          body.append(run("[" + alt + "]", flags | F_ITALIC));
        }
        case Link l -> {
          String url = l.getDestination();
          if (url != null && url.matches("(?i)(https?|mailto):.*")) {
            links.add(url);
            body.append("<w:hyperlink r:id=\"rIdLink").append(links.size()).append("\">");
            inlines(l, flags | F_LINK, url);
            body.append("</w:hyperlink>");
          } else {
            inlines(l, flags, link);
          }
        }
        default -> inlines(n, flags, link);
      }
    }
  }

  private static String run(String text, int flags) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    StringBuilder s = new StringBuilder("<w:r>");
    if (flags != F_NONE) {
      s.append("<w:rPr>");
      if ((flags & F_LINK) != 0) {
        s.append("<w:rStyle w:val=\"Hyperlink\"/>");
      }
      if ((flags & F_CODE) != 0) {
        s.append(
            "<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/>"
                + "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F2F2F2\"/>");
      }
      if ((flags & F_BOLD) != 0) {
        s.append("<w:b/>");
      }
      if ((flags & F_ITALIC) != 0) {
        s.append("<w:i/>");
      }
      if ((flags & F_STRIKE) != 0) {
        s.append("<w:strike/>");
      }
      s.append("</w:rPr>");
    }
    // Табуляция — отдельным элементом, остальное — текстом с сохранением пробелов.
    String[] parts = text.split("\t", -1);
    for (int i = 0; i < parts.length; i++) {
      if (i > 0) {
        s.append("<w:tab/>");
      }
      if (!parts[i].isEmpty()) {
        s.append("<w:t xml:space=\"preserve\">").append(xml(parts[i])).append("</w:t>");
      }
    }
    return s.append("</w:r>").toString();
  }

  static String xml(String s) {
    StringBuilder out = new StringBuilder(s.length() + 16);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        default -> {
          // Управляющие символы XML не допускает.
          if (c >= 0x20 || c == '\n' || c == '\r') {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }

  // ---------- части пакета ----------

  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private static final String R =
      "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

  private String rels() {
    StringBuilder s =
        new StringBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rIdStyles\" Type=\""
                + R
                + "/styles\" Target=\"styles.xml\"/>");
    for (int i = 0; i < links.size(); i++) {
      s.append("<Relationship Id=\"rIdLink")
          .append(i + 1)
          .append("\" Type=\"")
          .append(R)
          .append("/hyperlink\" Target=\"")
          .append(xml(links.get(i)))
          .append("\" TargetMode=\"External\"/>");
    }
    return s.append("</Relationships>").toString();
  }

  private static String core(String title) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<cp:coreProperties"
        + " xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\""
        + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>"
        + xml(title == null ? "" : title)
        + "</dc:title><dc:creator>campus</dc:creator></cp:coreProperties>";
  }

  private static final String CONTENT_TYPES =
      "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
          + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
          + "<Default Extension=\"rels\""
          + " ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
          + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
          + "<Override PartName=\"/word/document.xml\""
          + " ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
          + "<Override PartName=\"/word/styles.xml\""
          + " ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
          + "<Override PartName=\"/docProps/core.xml\""
          + " ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
          + "</Types>";

  private static final String ROOT_RELS =
      "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
          + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
          + "<Relationship Id=\"rId1\" Type=\""
          + R
          + "/officeDocument\" Target=\"word/document.xml\"/>"
          + "<Relationship Id=\"rId2\""
          + " Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\""
          + " Target=\"docProps/core.xml\"/>"
          + "</Relationships>";

  private static String heading(int level, int size, int before) {
    return "<w:style w:type=\"paragraph\" w:styleId=\"Heading"
        + level
        + "\"><w:name w:val=\"heading "
        + level
        + "\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>"
        + "<w:pPr><w:keepNext/><w:spacing w:before=\""
        + before
        + "\" w:after=\"120\"/><w:outlineLvl w:val=\""
        + (level - 1)
        + "\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\""
        + size
        + "\"/></w:rPr></w:style>";
  }

  private static final String STYLES =
      "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
          + "<w:styles xmlns:w=\""
          + W
          + "\"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\""
          + " w:hAnsi=\"Calibri\" w:cs=\"Calibri\" w:eastAsia=\"Calibri\"/><w:sz w:val=\"24\"/>"
          + "<w:lang w:val=\"ru-RU\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>"
          + "<w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr>"
          + "</w:pPrDefault></w:docDefaults>"
          + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">"
          + "<w:name w:val=\"Normal\"/><w:qFormat/></w:style>"
          + heading(1, 36, 360)
          + heading(2, 30, 280)
          + heading(3, 26, 240)
          + heading(4, 24, 200)
          + "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List"
          + " Paragraph\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:spacing w:after=\"60\"/>"
          + "</w:pPr></w:style>"
          + "<w:style w:type=\"paragraph\" w:styleId=\"Code\"><w:name w:val=\"Code\"/>"
          + "<w:basedOn w:val=\"Normal\"/><w:pPr><w:spacing w:after=\"0\" w:line=\"240\""
          + " w:lineRule=\"auto\"/><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F2F2F2\"/>"
          + "</w:pPr><w:rPr><w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\""
          + " w:cs=\"Consolas\"/><w:sz w:val=\"20\"/></w:rPr></w:style>"
          + "<w:style w:type=\"paragraph\" w:styleId=\"Quote\"><w:name w:val=\"Quote\"/>"
          + "<w:basedOn w:val=\"Normal\"/><w:pPr><w:pBdr><w:left w:val=\"single\" w:sz=\"18\""
          + " w:space=\"8\" w:color=\"BFBFBF\"/></w:pBdr><w:ind w:left=\"360\"/></w:pPr>"
          + "<w:rPr><w:i/><w:color w:val=\"595959\"/></w:rPr></w:style>"
          + "<w:style w:type=\"character\" w:styleId=\"Hyperlink\"><w:name"
          + " w:val=\"Hyperlink\"/><w:rPr><w:color w:val=\"0563C1\"/><w:u w:val=\"single\"/>"
          + "</w:rPr></w:style>"
          + "<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/>"
          + "<w:tblPr><w:tblBorders><w:top w:val=\"single\" w:sz=\"4\" w:color=\"BFBFBF\"/>"
          + "<w:left w:val=\"single\" w:sz=\"4\" w:color=\"BFBFBF\"/><w:bottom w:val=\"single\""
          + " w:sz=\"4\" w:color=\"BFBFBF\"/><w:right w:val=\"single\" w:sz=\"4\""
          + " w:color=\"BFBFBF\"/><w:insideH w:val=\"single\" w:sz=\"4\" w:color=\"BFBFBF\"/>"
          + "<w:insideV w:val=\"single\" w:sz=\"4\" w:color=\"BFBFBF\"/></w:tblBorders>"
          + "<w:tblCellMar><w:left w:w=\"108\" w:type=\"dxa\"/><w:right w:w=\"108\""
          + " w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>"
          + "</w:styles>";

  private static void put(ZipOutputStream zip, String name, String content) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  /** Это конспект в Markdown: по типу или по расширению. */
  public static boolean isMarkdown(String mime, String name) {
    String m = mime == null ? "" : mime;
    String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
    return m.equals("text/markdown")
        || m.equals("text/x-web-markdown")
        || n.endsWith(".md")
        || n.endsWith(".markdown");
  }
}
