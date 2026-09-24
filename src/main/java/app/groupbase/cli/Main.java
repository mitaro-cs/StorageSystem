package app.groupbase.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;

/** Точка входа: {@code groupbase <команда>}. Без аргументов печатает справку. */
@Command(
    name = "groupbase",
    mixinStandardHelpOptions = true,
    versionProvider = Main.Version.class,
    description = "Сервис для студенческих групп: новости, ДЗ, материалы.",
    subcommands = {
      ServeCommand.class,
      InitCommand.class,
      UserCommand.class,
      BackupCommand.class,
      RestoreCommand.class,
      DoctorCommand.class,
      SeedCommand.class,
      DesktopCommand.class,
      CommandLine.HelpCommand.class
    })
public class Main implements Runnable {

  @CommandLine.Spec CommandLine.Model.CommandSpec spec;

  public static void main(String[] args) {
    int code = commandLine().execute(args);
    System.exit(code);
  }

  static CommandLine commandLine() {
    return new CommandLine(new Main()).setCaseInsensitiveEnumValuesAllowed(true);
  }

  @Override
  public void run() {
    spec.commandLine().usage(System.out);
  }

  /** Версия из MANIFEST.MF (Implementation-Version), в разработке — "dev". */
  public static String version() {
    String v = Main.class.getPackage().getImplementationVersion();
    return v == null ? "dev" : v;
  }

  static final class Version implements IVersionProvider {
    @Override
    public String[] getVersion() {
      return new String[] {"groupbase " + version()};
    }
  }
}
