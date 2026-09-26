import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.File;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * Рисует PNG-иконки из логотипа web/static/logo.svg (Атлант с глобусом на белом): для PWA и
 * монохромный значок для строки состояния Android. Запуск: java scripts/Icons.java web/static/icons
 *
 * <p>{@code java scripts/Icons.java desktop <каталог>} — исходники для приложения на компьютере:
 * значок 1024 с полями по сетке macOS (из него {@code tauri icon} делает .icns и .ico) и значки для
 * строки меню и трея.
 */
public class Icons {

  /** Логотип в координатах logo.svg (квадрат 736). */
  record Logo(Shape globe, Shape grid, float line, Shape figure, float gap, Shape eye, Color ink) {}

  static final double SIDE = 736;

  public static void main(String[] args) throws Exception {
    Logo logo = read(new File("web/static/logo.svg"));
    if (args.length > 0 && args[0].equals("desktop")) {
      File dir = new File(args.length > 1 ? args[1] : "desktop/src-tauri/icons-src");
      dir.mkdirs();
      desktop(logo, 1024, new File(dir, "app-1024.png"));
      tray(logo, 44, Color.BLACK, new File(dir, "tray-template.png"));
      tray(logo, 32, Color.WHITE, new File(dir, "tray-light.png"));
      File layers = new File(dir.getParentFile(), "icons/AppIcon.icon/Assets");
      layers.mkdirs();
      layer(logo, 1024, new File(layers, "logo.png"));
      return;
    }
    if (args.length > 0 && args[0].equals("variants")) {
      variants(logo, new File("web/static"));
      return;
    }
    File dir = new File(args.length > 0 ? args[0] : "web/static/icons");
    dir.mkdirs();
    for (int size : new int[] {180, 192, 512}) {
      icon(logo, size, 1, new File(dir, "icon-" + size + ".png"));
    }
    // maskable: всё важное — в круге 80% от центра, остальное система может обрезать.
    icon(logo, 512, 0.9, new File(dir, "maskable-512.png"));
    badge(logo, 96, new File(dir, "badge-96.png"));
  }

  /**
   * Вариант значка на выбор (Профиль → Оформление → Значок): плитка цветом или градиентом, логотип
   * белый, сетка и зазоры — цвета плитки. «Светлый» — это обычные icons/icon-*.png.
   */
  record Variant(String id, Color from, Color to, Color ink) {}

  static final Variant[] VARIANTS = {
    new Variant("dark", new Color(0x2a2a2e), new Color(0x0c0c0e), Color.WHITE),
    new Variant("ocean", new Color(0x2f80ff), new Color(0x1238a8), Color.WHITE),
    new Variant("forest", new Color(0x2fb67c), new Color(0x11663f), Color.WHITE),
    new Variant("sunset", new Color(0xff9f43), new Color(0xee4d7e), Color.WHITE),
    new Variant("grape", new Color(0xa86bff), new Color(0x5a22c9), Color.WHITE)
  };

  /** Для каждого варианта: SVG для вкладки, PNG для экрана «Домой» и свой манифест. */
  static void variants(Logo logo, File statics) throws Exception {
    File dir = new File(statics, "icons/v");
    dir.mkdirs();
    String svg = Files.readString(new File(statics, "favicon.svg").toPath());
    String manifest = Files.readString(new File(statics, "manifest.webmanifest").toPath());
    for (Variant v : VARIANTS) {
      Files.writeString(new File(dir, v.id() + ".svg").toPath(), variantSvg(svg, v));
      for (int size : new int[] {180, 192, 512}) {
        variantPng(logo, v, size, 1, new File(dir, v.id() + "-" + size + ".png"));
      }
      variantPng(logo, v, 512, 0.9, new File(dir, v.id() + "-maskable-512.png"));
      Files.writeString(
          new File(statics, "manifest-" + v.id() + ".webmanifest").toPath(),
          manifest
              .replaceAll("/icons/icon-(\\d+)\\.png", "/icons/v/" + v.id() + "-$1.png")
              .replace("/icons/maskable-512.png", "/icons/v/" + v.id() + "-maskable-512.png"));
    }
  }

  static String hex(Color c) {
    return String.format("#%06x", c.getRGB() & 0xffffff);
  }

  /** favicon.svg в цветах варианта: плитка — градиент, «дыры» (сетка, зазор, глаз) — тоже он. */
  static String variantSvg(String svg, Variant v) {
    String defs =
        "<defs><linearGradient id=\"tile\" gradientUnits=\"userSpaceOnUse\" x1=\"66\" y1=\"72\""
            + " x2=\"666\" y2=\"672\"><stop offset=\"0\" stop-color=\""
            + hex(v.from())
            + "\"/><stop offset=\"1\" stop-color=\""
            + hex(v.to())
            + "\"/></linearGradient></defs>";
    String ink = String.format("#%06x", logoInk(svg));
    return svg.replaceFirst("(<svg[^>]*>)", "$1\n" + defs)
        .replace("\"" + ink + "\"", "\"" + hex(v.ink()) + "\"")
        .replace("\"#fff\"", "\"url(#tile)\"");
  }

  static int logoInk(String svg) {
    Matcher m = Pattern.compile("id=\"globe\"[^>]* fill=\"#([0-9a-f]{6})\"").matcher(svg);
    if (!m.find()) {
      throw new IllegalStateException("В favicon.svg нет цвета глобуса");
    }
    return Integer.parseInt(m.group(1), 16);
  }

  static void variantPng(Logo logo, Variant v, int size, double scale, File out) throws Exception {
    BufferedImage img = canvas(size);
    Graphics2D g = graphics(img);
    g.setPaint(new java.awt.GradientPaint(0, 0, v.from(), size, size, v.to()));
    g.fillRect(0, 0, size, size);
    double s = size / SIDE * scale;
    g.translate((size - SIDE * s) / 2, (size - SIDE * s) / 2);
    g.scale(s, s);
    g.setColor(v.ink());
    g.fill(silhouette(logo, logo.line(), logo.gap()));
    g.dispose();
    ImageIO.write(img, "png", out);
  }

  /** Квадрат с белым фоном, логотип как в оригинале (scale 1) или уменьшенный к центру. */
  static void icon(Logo logo, int size, double scale, File out) throws Exception {
    BufferedImage img = canvas(size);
    Graphics2D g = graphics(img);
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, size, size);
    double s = size / SIDE * scale;
    g.translate((size - SIDE * s) / 2, (size - SIDE * s) / 2);
    g.scale(s, s);
    draw(g, logo);
    g.dispose();
    ImageIO.write(img, "png", out);
  }

  /** Значок приложения для macOS и Windows: белый скруглённый квадрат 824/1024 с мягкой тенью. */
  static void desktop(Logo logo, int size, File out) throws Exception {
    double box = size * 824.0 / 1024;
    double off = (size - box) / 2;
    Shape tile = new RoundRectangle2D.Double(off, off, box, box, box * 0.45, box * 0.45);

    // Тень как у системных значков: размытая, чуть ниже плитки.
    BufferedImage shadow = canvas(size);
    Graphics2D sg = graphics(shadow);
    sg.setColor(new Color(0, 0, 0, 90));
    sg.translate(0, size * 0.012);
    sg.fill(tile);
    sg.dispose();
    shadow = blur(blur(shadow, (int) (size * 0.012)), (int) (size * 0.012));

    BufferedImage img = canvas(size);
    Graphics2D g = graphics(img);
    g.drawImage(shadow, 0, 0, null);
    g.setColor(Color.WHITE);
    g.fill(tile);
    // Тонкая граница: белая плитка не теряется на светлом фоне Dock и Finder.
    g.setColor(new Color(0, 0, 0, 22));
    g.setStroke(new BasicStroke((float) (size / 512.0)));
    g.draw(tile);
    double s = box / SIDE;
    g.translate(off, off);
    g.scale(s, s);
    draw(g, logo);
    g.dispose();
    ImageIO.write(img, "png", out);
  }

  /**
   * Слой логотипа для значка macOS 26+ (AppIcon.icon, формат Icon Composer): силуэт на прозрачном,
   * холст — вся плитка значка. Цвет задаёт icon.json: тёмный на белом фоне, в тёмном оформлении
   * Dock — белый на тёмном. Сетка, зазор вокруг фигуры и глаз — прозрачные, сквозь них виден фон.
   */
  static void layer(Logo logo, int size, File out) throws Exception {
    BufferedImage img = canvas(size);
    Graphics2D g = graphics(img);
    double s = size / SIDE;
    g.scale(s, s);
    g.setColor(logo.ink());
    g.fill(silhouette(logo, logo.line(), logo.gap()));
    g.dispose();
    ImageIO.write(img, "png", out);
  }

  /**
   * Значок в строке меню macOS (шаблон: система сама красит под тему) и в трее Windows: силуэт
   * логотипа. Линии сетки и зазоры утолщены, иначе при 22 точках их не видно.
   */
  static void tray(Logo logo, int size, Color color, File out) throws Exception {
    BufferedImage img = canvas(size);
    Graphics2D g = graphics(img);
    // Логотип (без полей) — на всю высоту значка.
    double s = size * 0.94 / 530;
    g.translate(size / 2.0 - 366.5 * s, size / 2.0 - 372.5 * s);
    g.scale(s, s);
    float bold = (float) (size / 26.0 / s);
    g.setColor(color);
    g.fill(silhouette(logo, bold, bold));
    g.dispose();
    ImageIO.write(img, "png", out);
  }

  /** Значок уведомления: белый силуэт на прозрачном фоне, Android сам перекрашивает. */
  static void badge(Logo logo, int size, File out) throws Exception {
    BufferedImage img = canvas(size);
    Graphics2D g = graphics(img);
    double s = size * 0.96 / 530;
    g.translate(size / 2.0 - 366.5 * s, size / 2.0 - 372.5 * s);
    g.scale(s, s);
    float bold = (float) (size / 40.0 / s);
    g.setColor(Color.WHITE);
    g.fill(silhouette(logo, bold, bold));
    g.dispose();
    ImageIO.write(img, "png", out);
  }

  /** Как на logo.svg: глобус, белая сетка в его пределах, фигура с белым зазором, глаз. */
  static void draw(Graphics2D g, Logo logo) {
    g.setColor(logo.ink());
    g.fill(logo.globe());
    Area grid = new Area(new BasicStroke(logo.line()).createStrokedShape(logo.grid()));
    grid.intersect(new Area(logo.globe()));
    g.setColor(Color.WHITE);
    g.fill(grid);
    g.fill(
        new BasicStroke(logo.gap() * 2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
            .createStrokedShape(logo.figure()));
    g.setColor(logo.ink());
    g.fill(logo.figure());
    g.setColor(Color.WHITE);
    g.fill(logo.eye());
  }

  /** Одноцветный силуэт: глобус минус сетка, фигура с зазором вокруг, глаз — дырка. */
  static Area silhouette(Logo logo, float line, float gap) {
    Area globe = new Area(logo.globe());
    Area grid = new Area(new BasicStroke(line).createStrokedShape(logo.grid()));
    globe.subtract(grid);
    Area figure = new Area(logo.figure());
    globe.subtract(
        new Area(
            new BasicStroke(gap * 2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
                .createStrokedShape(logo.figure())));
    globe.add(figure);
    globe.subtract(new Area(logo.eye()));
    return globe;
  }

  // ---------- чтение logo.svg ----------

  static Logo read(File svg) throws Exception {
    String s = Files.readString(svg.toPath());
    Shape globe = circle(tag(s, "globe"));
    String grid = tag(s, "grid");
    String figure = tag(s, "figure");
    return new Logo(
        globe,
        path(attr(grid, "d")),
        Float.parseFloat(attr(grid, "stroke-width")),
        path(attr(figure, "d")),
        Float.parseFloat(attr(figure, "stroke-width")) / 2,
        circle(tag(s, "eye")),
        Color.decode(attr(tag(s, "globe"), "fill")));
  }

  static String tag(String svg, String id) {
    Matcher m = Pattern.compile("<[a-z]+ id=\"" + id + "\"[^>]*>").matcher(svg);
    if (!m.find()) {
      throw new IllegalStateException("В logo.svg нет элемента id=" + id);
    }
    return m.group();
  }

  static String attr(String tag, String name) {
    Matcher m = Pattern.compile(" " + name + "=\"([^\"]*)\"").matcher(tag);
    if (!m.find()) {
      throw new IllegalStateException("Нет атрибута " + name + " в " + tag);
    }
    return m.group(1);
  }

  static Shape circle(String tag) {
    double cx = Double.parseDouble(attr(tag, "cx"));
    double cy = Double.parseDouble(attr(tag, "cy"));
    double r = Double.parseDouble(attr(tag, "r"));
    return new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r);
  }

  /** Абсолютные команды M, L, H, V, C, Z — других в logo.svg нет. */
  static Path2D path(String d) {
    Path2D p = new Path2D.Double(Path2D.WIND_EVEN_ODD);
    Matcher m = Pattern.compile("([MLHVCZ])([^MLHVCZ]*)").matcher(d);
    double x = 0;
    double y = 0;
    while (m.find()) {
      String[] t = m.group(2).trim().isEmpty() ? new String[0] : m.group(2).trim().split("[ ,]+");
      double[] v = new double[t.length];
      for (int i = 0; i < t.length; i++) {
        v[i] = Double.parseDouble(t[i]);
      }
      switch (m.group(1)) {
        case "M" -> p.moveTo(x = v[0], y = v[1]);
        case "L" -> p.lineTo(x = v[0], y = v[1]);
        case "H" -> p.lineTo(x = v[0], y);
        case "V" -> p.lineTo(x, y = v[0]);
        case "C" -> p.curveTo(v[0], v[1], v[2], v[3], x = v[4], y = v[5]);
        default -> p.closePath();
      }
    }
    return p;
  }

  // ---------- растр ----------

  static BufferedImage canvas(int size) {
    return new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
  }

  static Graphics2D graphics(BufferedImage img) {
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    return g;
  }

  /** Размытие по квадрату радиуса r (дважды подряд — почти по Гауссу). */
  static BufferedImage blur(BufferedImage src, int r) {
    int n = 2 * r + 1;
    float[] h = new float[n];
    java.util.Arrays.fill(h, 1f / n);
    BufferedImage a =
        new ConvolveOp(new Kernel(n, 1, h), ConvolveOp.EDGE_NO_OP, null).filter(src, null);
    return new ConvolveOp(new Kernel(1, n, h), ConvolveOp.EDGE_NO_OP, null).filter(a, null);
  }
}
