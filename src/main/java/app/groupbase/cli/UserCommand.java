package app.groupbase.cli;

import app.groupbase.accounts.AccountService;
import app.groupbase.accounts.PublicUrl;
import app.groupbase.config.GroupbaseProperties;
import app.groupbase.store.User;
import app.groupbase.store.UserStore;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Аккаунты из командной строки: когда администратор сам потерял доступ. */
@Command(
    name = "user",
    description = "Аккаунты: список, сброс 2FA и пароля.",
    mixinStandardHelpOptions = true,
    subcommands = {
      UserCommand.ListUsers.class,
      UserCommand.ResetTwoFactor.class,
      UserCommand.ResetPassword.class
    })
public class UserCommand implements Runnable {

  @Spec picocli.CommandLine.Model.CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  static User find(ConfigurableApplicationContext ctx, String login) {
    return ctx.getBean(UserStore.class)
        .findByUsername(login.strip().toLowerCase(java.util.Locale.ROOT))
        .filter(u -> u.status() != User.Status.DELETED)
        .orElse(null);
  }

  @Command(name = "list", description = "Все аккаунты: логин, имя, роль, 2FA.")
  static class ListUsers implements Callable<Integer> {
    @Mixin Target target;
    @Spec picocli.CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws Exception {
      PrintWriter out = spec.commandLine().getOut();
      try (var ctx = target.open()) {
        List<User> all = ctx.getBean(UserStore.class).listAll();
        if (all.isEmpty()) {
          out.println("Аккаунтов нет — сайт ещё не настроен (groupbase init).");
          return 0;
        }
        out.printf("%-24s %-32s %-14s %-10s %s%n", "ЛОГИН", "ИМЯ", "РОЛЬ САЙТА", "СТАТУС", "2FA");
        for (User u : all) {
          out.printf(
              "%-24s %-32s %-14s %-10s %s%n",
              u.username(),
              cut(u.displayName(), 32),
              u.instanceRole() == null ? "—" : u.instanceRole().id(),
              u.status().id(),
              u.totpEnabled() ? "вкл" : "—");
        }
        return 0;
      }
    }

    private static String cut(String s, int n) {
      return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
  }

  @Command(
      name = "reset-2fa",
      description =
          "Выключить двухфакторную защиту (если потерян телефон и резервные коды)."
              + " Все сеансы человека завершатся.")
  static class ResetTwoFactor implements Callable<Integer> {
    @Mixin Target target;
    @Spec picocli.CommandLine.Model.CommandSpec spec;

    @Parameters(paramLabel = "ЛОГИН")
    String login;

    @Override
    public Integer call() throws Exception {
      PrintWriter out = spec.commandLine().getOut();
      try (var ctx = target.open()) {
        User u = find(ctx, login);
        if (u == null) {
          spec.commandLine().getErr().println("Нет аккаунта с логином «" + login + "»");
          return 1;
        }
        if (!u.totpEnabled()) {
          out.println("У «" + u.username() + "» 2FA и так выключена.");
          return 0;
        }
        ctx.getBean(AccountService.class).resetTwoFactorSystem(u.id());
        out.println("2FA у «" + u.username() + "» выключена, все сеансы завершены.");
        out.println("Войдите паролем и включите 2FA заново: Профиль → Безопасность.");
        return 0;
      }
    }
  }

  @Command(
      name = "reset-password",
      description = "Одноразовая ссылка, по которой человек задаст новый пароль.")
  static class ResetPassword implements Callable<Integer> {
    @Mixin Target target;
    @Spec picocli.CommandLine.Model.CommandSpec spec;

    @Parameters(paramLabel = "ЛОГИН")
    String login;

    @Override
    public Integer call() throws Exception {
      PrintWriter out = spec.commandLine().getOut();
      try (var ctx = target.open()) {
        User u = find(ctx, login);
        if (u == null) {
          spec.commandLine().getErr().println("Нет аккаунта с логином «" + login + "»");
          return 1;
        }
        String path = ctx.getBean(AccountService.class).resetLinkSystem(u.id());
        String base = ctx.getBean(PublicUrl.class).get().orElse(null);
        if (base == null) {
          var http = ctx.getBean(GroupbaseProperties.class).http();
          String host = http.address().startsWith("127.") ? "localhost" : http.address();
          base = "http://" + host + ":" + http.port();
        }
        out.println("Ссылка для «" + u.username() + "» (одноразовая):");
        out.println(base + path);
        return 0;
      }
    }
  }
}
