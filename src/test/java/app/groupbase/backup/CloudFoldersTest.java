package app.groupbase.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CloudFoldersTest {

  @Test
  void findsCloudDrivesInHome(@TempDir Path home) throws IOException {
    Files.createDirectories(home.resolve("Library/Mobile Documents/com~apple~CloudDocs"));
    Files.createDirectories(home.resolve("Library/CloudStorage/YandexDisk-omar"));
    Files.createDirectories(home.resolve("OneDrive"));
    Files.createDirectories(home.resolve("Documents"));

    var found = CloudFolders.detect(home, Map.of());
    assertThat(found)
        .extracting(CloudFolders.Folder::label)
        .containsExactly("iCloud Drive", "Яндекс Диск", "OneDrive");
    assertThat(found).allSatisfy(f -> assertThat(f.path()).isAbsolute());
  }

  @Test
  void nothingWhenNoCloud(@TempDir Path home) {
    assertThat(CloudFolders.detect(home, Map.of())).isEmpty();
  }
}
