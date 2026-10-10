package app.groupbase.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DrivesTest {

  @Test
  void offersWritableDrivesExceptTheDataDisk(@TempDir Path dir) throws IOException {
    Path usb = Files.createDirectories(dir.resolve("KINGSTON"));
    Path data = Files.createDirectories(dir.resolve("Macintosh HD"));
    Path missing = dir.resolve("Отключён");

    var found = Drives.detect(List.of(usb, data, missing, usb), p -> p.equals(data));
    assertThat(found).extracting(Drives.Drive::label).containsExactly("KINGSTON");
    assertThat(found.get(0).path()).isEqualTo(usb.toAbsolutePath().normalize());
    assertThat(found.get(0).free()).isPositive();
  }
}
