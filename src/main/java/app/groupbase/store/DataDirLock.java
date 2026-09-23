package app.groupbase.store;

import app.groupbase.config.GroupbaseProperties;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Один каталог данных — один сервер. Работающий сервер держит блокировку {@code data/.lock}: второй
 * экземпляр не запустится, а {@code groupbase restore} откажется перезаписывать базу под ним.
 */
@Component
@Profile("serve")
public class DataDirLock implements DisposableBean {

  static final String FILE = ".lock";

  private final FileChannel channel;
  private final FileLock lock;

  public DataDirLock(GroupbaseProperties props) throws IOException {
    Path dir = props.dataDir();
    Files.createDirectories(dir);
    channel =
        FileChannel.open(dir.resolve(FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    FileLock l;
    try {
      l = channel.tryLock();
    } catch (OverlappingFileLockException e) {
      l = null;
    }
    if (l == null) {
      channel.close();
      throw new IllegalStateException(
          "Сервер с каталогом данных " + dir.toAbsolutePath() + " уже запущен");
    }
    lock = l;
  }

  /** Занят ли каталог работающим сервером (для команд CLI). */
  public static boolean held(Path dataDir) {
    Path f = dataDir.resolve(FILE);
    if (!Files.exists(f)) {
      return false;
    }
    try (FileChannel ch = FileChannel.open(f, StandardOpenOption.WRITE)) {
      FileLock l = ch.tryLock();
      if (l == null) {
        return true;
      }
      l.release();
      return false;
    } catch (OverlappingFileLockException e) {
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  @Override
  public void destroy() throws IOException {
    lock.release();
    channel.close();
  }
}
