package app.groupbase.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.groupbase.TestProps;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataDirLockTest {

  @Test
  void secondServerOnSameDataDirIsRefused(@TempDir Path dir) throws IOException {
    var props = TestProps.of(Map.of("groupbase.data-dir", dir.toString()));
    DataDirLock first = new DataDirLock(props);
    assertThat(DataDirLock.held(dir)).isTrue();
    assertThatThrownBy(() -> new DataDirLock(props))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("уже запущен");
    first.destroy();
    assertThat(DataDirLock.held(dir)).isFalse();
    // После остановки каталог снова свободен.
    new DataDirLock(props).destroy();
  }

  @Test
  void freshDirIsNotHeld(@TempDir Path dir) {
    assertThat(DataDirLock.held(dir)).isFalse();
    assertThat(DataDirLock.held(dir.resolve("нет такого"))).isFalse();
  }
}
