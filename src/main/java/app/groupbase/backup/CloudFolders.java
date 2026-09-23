package app.groupbase.backup;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Папки облачных дисков на компьютере хоста: копии, сложенные туда, сами уедут в облако. Выбор — из
 * найденных папок, путь вручную не вводится (и не принимается из веба).
 */
public final class CloudFolders {

  public record Folder(String label, Path path) {}

  private CloudFolders() {}

  public static List<Folder> detect() {
    return detect(Path.of(System.getProperty("user.home")), System.getenv());
  }

  static List<Folder> detect(Path home, Map<String, String> env) {
    Map<Path, String> found = new LinkedHashMap<>();
    add(found, home.resolve("Library/Mobile Documents/com~apple~CloudDocs"), "iCloud Drive");
    Path storage = home.resolve("Library/CloudStorage");
    if (Files.isDirectory(storage)) {
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(storage)) {
        for (Path p : ds) {
          add(found, p, label(p.getFileName().toString()));
        }
      } catch (IOException e) {
        // Нет доступа — просто не предлагаем.
      }
    }
    for (String name :
        List.of(
            "Yandex.Disk.localized",
            "Yandex.Disk",
            "YandexDisk",
            "OneDrive",
            "Dropbox",
            "Google Drive",
            "iCloudDrive")) {
      add(found, home.resolve(name), label(name));
    }
    for (String var : List.of("OneDrive", "OneDriveConsumer", "OneDriveCommercial")) {
      String v = env.get(var);
      if (v != null && !v.isBlank()) {
        add(found, Path.of(v), "OneDrive");
      }
    }
    List<Folder> out = new ArrayList<>();
    found.forEach((p, l) -> out.add(new Folder(l, p)));
    return out;
  }

  private static void add(Map<Path, String> found, Path p, String label) {
    if (Files.isDirectory(p) && Files.isWritable(p)) {
      found.putIfAbsent(p.toAbsolutePath().normalize(), label);
    }
  }

  static String label(String name) {
    String n = name.toLowerCase(Locale.ROOT);
    if (n.contains("yandex")) {
      return "Яндекс Диск";
    }
    if (n.contains("onedrive")) {
      return "OneDrive";
    }
    if (n.contains("google")) {
      return "Google Диск";
    }
    if (n.contains("dropbox")) {
      return "Dropbox";
    }
    if (n.contains("icloud")) {
      return "iCloud Drive";
    }
    return name;
  }
}
