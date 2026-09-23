import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Рисует PNG-иконки PWA из того же логотипа, что favicon.svg (три слоя на чёрном квадрате), и
 * монохромный значок для строки состояния Android. Запуск: java scripts/Icons.java web/static/icons
 */
public class Icons {
  public static void main(String[] args) throws Exception {
    File dir = new File(args.length > 0 ? args[0] : "web/static/icons");
    dir.mkdirs();
    for (int size : new int[] {180, 192, 512}) {
      write(size, false, new File(dir, "icon-" + size + ".png"));
    }
    write(512, true, new File(dir, "maskable-512.png"));
    badge(96, new File(dir, "badge-96.png"));
  }

  /** Значок уведомления: белый логотип на прозрачном фоне, Android сам перекрашивает. */
  static void badge(int size, File out) throws Exception {
    BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    // Логотип занимает x 8..24, y 7..25 сетки 32×32 — растягиваем на весь значок.
    double scale = size / 20.0;
    g.scale(scale, scale);
    g.translate(-6, -6);
    g.setColor(Color.WHITE);
    g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g.draw(logo());
    g.dispose();
    ImageIO.write(img, "png", out);
  }

  static GeneralPath logo() {
    GeneralPath p = new GeneralPath();
    p.moveTo(9, 11.5);
    p.lineTo(16, 8);
    p.lineTo(23, 11.5);
    p.lineTo(16, 15);
    p.closePath();
    p.moveTo(9, 16);
    p.lineTo(16, 19.5);
    p.lineTo(23, 16);
    p.moveTo(9, 20.5);
    p.lineTo(16, 24);
    p.lineTo(23, 20.5);
    return p;
  }

  static void write(int size, boolean maskable, File out) throws Exception {
    BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    Color accent = new Color(0x0d, 0x0d, 0x0f);
    g.setColor(accent);
    if (maskable) {
      g.fillRect(0, 0, size, size);
    } else {
      double r = size * 9.0 / 32;
      g.fill(new RoundRectangle2D.Double(0, 0, size, size, r, r));
    }
    // Логотип нарисован в сетке 32×32; для maskable — в безопасной зоне 80%.
    double scale = maskable ? size * 0.62 / 32 : size / 32.0;
    double off = maskable ? (size - 32 * scale) / 2 : 0;
    g.translate(off, off);
    g.scale(scale, scale);
    g.setColor(Color.WHITE);
    g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g.draw(logo());
    g.dispose();
    ImageIO.write(img, "png", out);
  }
}
