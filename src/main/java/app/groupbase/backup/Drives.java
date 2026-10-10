package app.groupbase.backup;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Второй диск для копий (1.0.2): флешка, внешний или другой внутренний диск компьютера хоста.
 * Только найденные здесь, путь вручную не вводится (как у облачных папок). Диск с данными сайта не
 * предлагается – копия рядом с данными от поломки диска не спасает.
 */
public final class Drives {

  public record Drive(String label, Path path, long free) {}

  private Drives() {}

  public static List<Drive> detect(Path dataDir) {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    List<Path> roots = new ArrayList<>();
    if (os.contains("win")) {
      for (File f : File.listRoots()) {
        roots.add(f.toPath());
      }
    } else if (os.contains("mac")) {
      children(Path.of("/Volumes"), roots);
    } else {
      String user = System.getProperty("user.name", "");
      children(Path.of("/media", user), roots);
      children(Path.of("/run/media", user), roots);
      children(Path.of("/media"), roots);
      children(Path.of("/mnt"), roots);
    }
    FileStore data = store(dataDir);
    Path system = Path.of("/");
    return detect(roots, root -> Objects.equals(store(root), data) || same(root, system));
  }

  /** Из кандидатов – доступные для записи и не на том же диске, что данные и система. */
  static List<Drive> detect(List<Path> candidates, Predicate<Path> excluded) {
    Map<Path, Drive> out = new LinkedHashMap<>();
    for (Path p : candidates) {
      Path root = p.toAbsolutePath().normalize();
      if (!Files.isDirectory(root) || !Files.isWritable(root) || out.containsKey(root)) {
        continue;
      }
      FileStore fs = store(root);
      if (fs == null || excluded.test(root)) {
        continue;
      }
      long free;
      try {
        free = fs.getUsableSpace();
      } catch (IOException e) {
        continue;
      }
      out.put(root, new Drive(label(root, fs), root, free));
    }
    return List.copyOf(out.values());
  }

  /** «KINGSTON (E:)», «Флешка» – имя тома и, на Windows, буква диска. */
  static String label(Path root, FileStore fs) {
    String name = fs.name() == null ? "" : fs.name().strip();
    Path file = root.getFileName();
    if (file == null) {
      // Корень диска Windows: «E:\».
      String letter = root.toString().replace("\\", "").replace("/", "");
      return name.isEmpty() ? "Диск " + letter : name + " (" + letter + ")";
    }
    return file.toString();
  }

  private static void children(Path dir, List<Path> into) {
    if (!Files.isDirectory(dir)) {
      return;
    }
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
      for (Path p : ds) {
        into.add(p);
      }
    } catch (IOException e) {
      // Нет доступа – не предлагаем.
    }
  }

  private static FileStore store(Path p) {
    try {
      return Files.getFileStore(p);
    } catch (IOException e) {
      return null;
    }
  }

  private static boolean same(Path a, Path b) {
    try {
      return Files.isSameFile(a, b);
    } catch (IOException e) {
      return false;
    }
  }
}
