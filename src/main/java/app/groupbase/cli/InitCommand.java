package app.groupbase.cli;

import app.groupbase.accounts.GroupService;
import app.groupbase.accounts.Names;
import app.groupbase.accounts.SetupService;
import app.groupbase.store.User;
import app.groupbase.web.ApiException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Первичная настройка из терминала — вместо страницы /setup со ссылкой из лога. */
@Command(
    name = "init",
    description = "Первичная настройка: группа и аккаунт старосты (он же администратор сайта).",
    mixinStandardHelpOptions = true)
public class InitCommand implements Callable<Integer> {

  @Mixin Target target;
  @Spec picocli.CommandLine.Model.CommandSpec spec;

  @Option(names = "--group", required = true, paramLabel = "НАЗВАНИЕ", description = "БИК2401")
  String group;

  @Option(names = "--university", paramLabel = "ВУЗ", defaultValue = "МТУСИ")
  String university;

  @Option(names = "--course", paramLabel = "КУРС")
  Integer course;

  @Option(
      names = "--name",
      required = true,
      paramLabel = "ФИО",
      description = "ФИО старосты: «Иванова Анна Сергеевна».")
  String name;

  @Option(
      names = "--login",
      paramLabel = "ЛОГИН",
      description = "Логин латиницей; по умолчанию — из ФИО.")
  String login;

  @Option(
      names = "--multi",
      description = "Несколько групп на одном сайте (поток); по умолчанию — одна группа.")
  boolean multi;

  @Option(
      names = "--password-stdin",
      description = "Прочитать пароль из стандартного ввода (для скриптов).")
  boolean passwordStdin;

  @Override
  public Integer call() throws Exception {
    PrintWriter out = spec.commandLine().getOut();
    PrintWriter err = spec.commandLine().getErr();
    String password = password(err);
    if (password == null) {
      return 1;
    }
    try (var ctx = target.open()) {
      SetupService setup = ctx.getBean(SetupService.class);
      if (!setup.needed()) {
        err.println("Сайт уже настроен. Аккаунты — groupbase user list.");
        return 1;
      }
      String username = login == null || login.isBlank() ? Names.suggestUsername(name) : login;
      User admin =
          setup.setup(
              new SetupService.Request(
                  multi ? "multi" : "single",
                  "",
                  new GroupService.GroupInput(group, university, course),
                  username,
                  name,
                  password));
      out.println("Готово: группа «" + group + "», староста — " + admin.displayName() + ".");
      out.println("Логин для входа: " + admin.username());
      return 0;
    } catch (ApiException e) {
      err.println(e.getMessage());
      return 1;
    }
  }

  private String password(PrintWriter err) throws java.io.IOException {
    if (passwordStdin) {
      String line =
          new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
      return line == null ? null : line.strip();
    }
    java.io.Console console = System.console();
    if (console == null) {
      err.println("Нет терминала для ввода пароля — используйте --password-stdin");
      return null;
    }
    char[] first = console.readPassword("Пароль старосты (от 10 символов): ");
    char[] second = console.readPassword("Ещё раз: ");
    if (first == null || second == null || !java.util.Arrays.equals(first, second)) {
      err.println("Пароли не совпали");
      return null;
    }
    return new String(first);
  }
}
