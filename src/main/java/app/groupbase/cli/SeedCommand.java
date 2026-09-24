package app.groupbase.cli;

import app.groupbase.accounts.AccountService;
import app.groupbase.accounts.GroupService;
import app.groupbase.accounts.SetupService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.GroupRole;
import app.groupbase.config.GroupbaseProperties;
import app.groupbase.content.HomeworkService;
import app.groupbase.content.MaterialService;
import app.groupbase.content.NewsService;
import app.groupbase.content.SubjectService;
import app.groupbase.files.FileStore;
import app.groupbase.files.StoredFile;
import app.groupbase.store.GroupStore;
import app.groupbase.store.User;
import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Spec;

/** Демо-группа для разработки и скриншотов: только в пустом каталоге данных. */
@Command(
    name = "seed",
    description =
        "Заполнить пустой сайт демо-данными: группа, предметы, задания, новости, сессия."
            + " Пароль у всех — "
            + SeedCommand.PASSWORD
            + ".",
    mixinStandardHelpOptions = true)
public class SeedCommand implements Callable<Integer> {

  static final String PASSWORD = "demo-password";

  @Mixin Target target;
  @Spec picocli.CommandLine.Model.CommandSpec spec;

  private record S(String name, String teacher, String color) {}

  private static final List<S> SUBJECTS =
      List.of(
          new S("Высшая математика", "Петров Андрей Викторович", "#3446d4"),
          new S("Физика", "Соколова Мария Игоревна", "#0891b2"),
          new S("Программирование на Java", "Ким Олег Сергеевич", "#0e9f6e"),
          new S("Сети связи", "Волков Дмитрий Павлович", "#7c3aed"),
          new S("Английский язык", "Смит Анна Джоновна", "#db2777"),
          new S("История России", "Орлова Елена Андреевна", "#d97706"),
          new S("Базы данных", "Лебедев Игорь Олегович", "#65a30d"),
          new S("Физическая культура", "Никитин Сергей Сергеевич", "#6b7280"));

  private static final List<String> STUDENTS =
      List.of(
          "Смирнов Артём Игоревич",
          "Кузнецова Дарья Андреевна",
          "Попов Максим Олегович",
          "Васильева Полина Сергеевна",
          "Новиков Егор Дмитриевич",
          "Морозова Алина Викторовна",
          "Фёдоров Илья Александрович",
          "Козлова Софья Павловна");

  @Override
  public Integer call() throws Exception {
    PrintWriter out = spec.commandLine().getOut();
    try (var ctx = target.open()) {
      SetupService setup = ctx.getBean(SetupService.class);
      if (!setup.needed()) {
        spec.commandLine().getErr().println("Сайт уже настроен — seed заполняет только пустой.");
        return 1;
      }
      ZoneId zone = ctx.getBean(GroupbaseProperties.class).timezone();
      User admin =
          setup.setup(
              new SetupService.Request(
                  "single",
                  "",
                  new GroupService.GroupInput("БИК2401", "МТУСИ", 2),
                  "ivanova",
                  "Иванова Анна Сергеевна",
                  PASSWORD));
      Actor actor =
          new Actor(
              admin.id(),
              admin.username(),
              admin.displayName(),
              admin.instanceRole(),
              false,
              null,
              null,
              true);
      long group = ctx.getBean(GroupStore.class).rolesOf(admin.id()).keySet().iterator().next();

      AccountService accounts = ctx.getBean(AccountService.class);
      for (int i = 0; i < STUDENTS.size(); i++) {
        String name = STUDENTS.get(i);
        accounts.createSystem(
            app.groupbase.accounts.Names.suggestUsername(name),
            name,
            PASSWORD,
            null,
            group,
            i == 0 ? GroupRole.DEPUTY : GroupRole.STUDENT);
      }

      SubjectService subjects = ctx.getBean(SubjectService.class);
      List<Long> ids = new ArrayList<>();
      for (S s : SUBJECTS) {
        ids.add(
            subjects
                .create(actor, group, new SubjectService.Input(s.name, s.teacher, s.color, null))
                .id());
      }

      LocalDate today = LocalDate.now(zone);
      HomeworkService homework = ctx.getBean(HomeworkService.class);
      record H(int subject, String kind, String title, String body, int days, int hour, int diff) {}
      List<H> tasks =
          List.of(
              new H(
                  0,
                  "homework",
                  "Интегралы: №412–420",
                  "Решить в тетради, сдать на паре.",
                  1,
                  9,
                  2),
              new H(2, "lab", "Лабораторная №3: коллекции", "Отчёт и код — в Moodle.", 3, 23, 3),
              new H(4, "homework", "Эссе «My future profession»", "180–220 слов.", 4, 10, 1),
              new H(
                  1,
                  "test",
                  "Контрольная: кинематика",
                  "Формулы — на обороте методички.",
                  6,
                  11,
                  2),
              new H(3, "homework", "Модель OSI — конспект", "Все 7 уровней с примерами.", 8, 9, 1),
              new H(6, "lab", "ER-диаграмма для библиотеки", "", 12, 23, 2),
              new H(5, "credit", "Зачёт по истории", "Список вопросов — в материалах.", 12, 10, 0),
              new H(7, "credit", "Зачёт по физкультуре", "Норматив: бег 1 км.", 14, 12, 0),
              new H(0, "exam", "Экзамен по высшей математике", "Билеты — в материалах.", 18, 9, 3),
              new H(2, "exam", "Экзамен по программированию", "Задача на ПК + теория.", 22, 10, 3),
              new H(3, "exam", "Экзамен по сетям связи", "", 26, 9, 2));
      String[] rooms = {"ауд. 305", "ауд. 214", "ауд. 118", "спортзал", "ауд. 402"};
      int r = 0;
      for (H h : tasks) {
        boolean exam = h.kind.equals("credit") || h.kind.equals("exam");
        long due = today.plusDays(h.days).atTime(h.hour, 0).atZone(zone).toInstant().toEpochMilli();
        homework.create(
            actor,
            new HomeworkService.Input(
                ids.get(h.subject),
                h.title,
                h.body,
                due,
                List.of(group),
                List.of(),
                h.diff,
                h.kind,
                exam ? rooms[r++ % rooms.length] : null));
      }
      ctx.getBean(GroupService.class)
          .setSession(
              actor,
              group,
              today.plusDays(12).atStartOfDay(zone).toInstant().toEpochMilli(),
              today.plusDays(28).atStartOfDay(zone).toInstant().toEpochMilli());

      NewsService news = ctx.getBean(NewsService.class);
      news.create(
          actor,
          new NewsService.Input(
              "Расписание сессии",
              "Зачёты и экзамены — на странице «Сессия». Консультации за день до экзамена.",
              List.of(group),
              null,
              true,
              false));
      news.create(
          actor,
          new NewsService.Input(
              "Пара по физике переносится",
              "В четверг пара начнётся в **11:40** в ауд. 214.",
              List.of(group),
              ids.get(1),
              false,
              true));
      news.create(
          actor,
          new NewsService.Input(
              "Сбор на субботник",
              "В субботу в 10:00 у главного входа. Перчатки выдадут.",
              List.of(group),
              null,
              false,
              false));

      MaterialService materials = ctx.getBean(MaterialService.class);
      materials.create(
          actor,
          ids.get(2),
          new MaterialService.Input(
              "link",
              "Документация Java 21",
              "Официальный справочник по стандартной библиотеке.",
              "https://docs.oracle.com/en/java/javase/21/docs/api/",
              null,
              null));
      StoredFile f =
          ctx.getBean(FileStore.class)
              .store(
                  new ByteArrayInputStream(
                      ("Вопросы к экзамену по высшей математике\n\n"
                              + "1. Предел функции. Замечательные пределы.\n"
                              + "2. Производная и её геометрический смысл.\n"
                              + "3. Неопределённый интеграл. Методы интегрирования.\n"
                              + "4. Определённый интеграл. Формула Ньютона — Лейбница.\n")
                          .getBytes(StandardCharsets.UTF_8)),
                  "Вопросы к экзамену.txt",
                  admin.id());
      materials.create(
          actor,
          ids.get(0),
          new MaterialService.Input("file", "Вопросы к экзамену", "", null, f.id(), null));

      out.println("Готово: группа БИК2401, " + (STUDENTS.size() + 1) + " человек.");
      out.println("Вход: ivanova (староста и администратор), пароль " + PASSWORD + ".");
      return 0;
    }
  }
}
