package app.groupbase;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Корневая конфигурация Spring. Запускается из {@link app.groupbase.cli.Main}. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GroupbaseApplication {}
