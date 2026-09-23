package app.groupbase.store;

import app.groupbase.config.GroupbaseProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteConfig;

/**
 * SQLite в режиме WAL. Транзакции открываются как IMMEDIATE: пишущая транзакция сразу берёт
 * блокировку и ждёт до {@code busy_timeout}, а не падает с SQLITE_BUSY при повышении блокировки.
 */
@Configuration
public class DatabaseConfig {

  @Bean(destroyMethod = "close")
  public HikariDataSource dataSource(GroupbaseProperties props) throws IOException {
    Files.createDirectories(props.dataDir());
    HikariConfig hc = new HikariConfig();
    hc.setPoolName("sqlite");
    hc.setJdbcUrl("jdbc:sqlite:" + props.databaseFile().toAbsolutePath());
    hc.setDataSourceProperties(sqliteConfig().toProperties());
    hc.setMaximumPoolSize(8);
    hc.setMinimumIdle(1);
    return new HikariDataSource(hc);
  }

  public static SQLiteConfig sqliteConfig() {
    SQLiteConfig cfg = new SQLiteConfig();
    cfg.setJournalMode(SQLiteConfig.JournalMode.WAL);
    cfg.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
    cfg.enforceForeignKeys(true);
    cfg.setBusyTimeout(5000);
    cfg.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
    cfg.setTempStore(SQLiteConfig.TempStore.MEMORY);
    return cfg;
  }

  /** Для кода, которому нужен DataSource до старта Spring (CLI restore, doctor). */
  public static DataSource standalone(GroupbaseProperties props) throws IOException {
    return new DatabaseConfig().dataSource(props);
  }
}
