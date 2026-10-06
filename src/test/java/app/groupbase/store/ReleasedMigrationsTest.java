package app.groupbase.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Выпущенные миграции не меняются (в 0.7.0 изменили V22 из 0.6.0 — базы 0.6 перестали открываться,
 * GB-206). Новая миграция — новый файл; добавьте его сюда вместе с суммой.
 */
class ReleasedMigrationsTest {

  private static final Map<String, String> RELEASED =
      Map.ofEntries(
          Map.entry(
              "V1__init.sql", "94bf59650e8607b4419d7cfa8ab7ac9b1da25964fe617937a3373ed88f446080"),
          Map.entry(
              "V2__accounts_groups.sql",
              "cd6ed13c10dcc769dc10092bb17afba3aca48e3161d7d2e3f910fb1afa292309"),
          Map.entry(
              "V3__subjects_news_homework.sql",
              "94802dbf4819fbcbbde73e936b096c057b28a55f97e70c8eaf0dc86272fe1886"),
          Map.entry(
              "V4__materials_files.sql",
              "0c1ad719a9f98c1a930c866763bcc494b18742b3f9e5d0480ba54e5110ddfa3f"),
          Map.entry(
              "V5__totp_drift.sql",
              "89d8061f8b3c26c053139d0024349087742415d04090fbf0ab83da5a92d4d918"),
          Map.entry(
              "V6__search.sql", "54143d91175b2f5974b0d828136d7511630b492116c763dcd52840aa86cda007"),
          Map.entry(
              "V7__recovery_codes.sql",
              "6e54c2c415ae55eb6e8d48830a683b95c5101f2e6173a5046a0dcf30230d584f"),
          Map.entry(
              "V8__notifications.sql",
              "857eb96497093c91b5884c26d0b2a6075a6a583e1cffe348b7d8786317f74cee"),
          Map.entry(
              "V9__sync.sql", "653fbd800e47f54bc11d444854d17648cc4fe26af12f6d8e2c2bd5d3fd32c9cb"),
          Map.entry(
              "V10__desktop.sql",
              "ef502b6a1e4302acdcea973d6c2608c70b1a161ab164683401fa9d6af9783212"),
          Map.entry(
              "V11__telegram_chats.sql",
              "8948b58cda4ff94b48ed301b553f90c379e9886433341c2adaec4d04bd89d60f"),
          Map.entry(
              "V12__icons_difficulty.sql",
              "8f30c3ccbdecb8330d51ac8b0607ee436076b72173f8f4aad072b70377cac664"),
          Map.entry(
              "V13__kinds_session.sql",
              "651ff103724fd91ab833bf122a4e40b618439105870a365337a25ee499421497"),
          Map.entry(
              "V14__passkeys.sql",
              "3ce0c532224b29790890aa0e674ce07d9bd07819b8385e690ef09e8862a3f327"),
          Map.entry(
              "V15__session_nav.sql",
              "0be207e7a916542caeca92b9276010411dd78126d89768fdbc673d5ac82d7ff6"),
          Map.entry(
              "V16__reports.sql",
              "8ebfa22d82b9227428ff7b1b8854eb6910de377845c9b784f797703846b86e8d"),
          Map.entry(
              "V17__subject_cover.sql",
              "2621d8b8797f6799ca0d41a4bf9fbe98e945bfdcb066dfe57736509ff16f712f"),
          Map.entry(
              "V18__onboarding.sql",
              "b67a02fd0b318649673988678a8b09bb05b79a6b74a1974a62e11815269df6c6"),
          Map.entry(
              "V19__subject_hidden.sql",
              "8606a9d415a4778140551018546dc647a6e145ddf370cc568153310338e2f60b"),
          Map.entry(
              "V20__post_attachments.sql",
              "d5f19aa38899ed40ae18130d30d5a30a849a9b57a6c278a22e8b0e69a55c8073"),
          Map.entry(
              "V21__lessons.sql",
              "4d7e8b2d5bfb73e250f296cf1c523779f4ddd0e8c45f030c0d4eed96cfdec9d9"),
          Map.entry(
              "V22__tips.sql", "8f7340f4c463a4f9616d6e61087c813ac1b997bf627bf059de35d04f9aae12ce"),
          Map.entry(
              "V23__material_notes.sql",
              "1f53cce95431ac615b54d07ea59233ab9c04eca874287f448f9a3895b574c6cd"),
          Map.entry(
              "V24__lesson_cancelled.sql",
              "7e7306894c82058562b23fe145ba42245a5856b3fcf1c125baf4b8fce7cdfe6c"),
          Map.entry(
              "V26__host_peers.sql",
              "8a03e60fba4418f951285660c96f80ff752ccd0b238f7f821528f98f1c0a2379"),
          Map.entry(
              "V27__homework_opens.sql",
              "aaaf5be4a19f77be7b886cc70f3e40a98ea6a8a9ab866b617733cf2a5cb217e1"));

  @Test
  void releasedMigrationsAreUnchanged() throws IOException, NoSuchAlgorithmException {
    Map<String, String> actual = new TreeMap<>();
    for (Resource r :
        new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*.sql")) {
      try (InputStream in = r.getInputStream()) {
        actual.put(
            r.getFilename(),
            HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())));
      }
    }
    for (var e : RELEASED.entrySet()) {
      assertThat(actual)
          .as("выпущенная миграция " + e.getKey())
          .containsEntry(e.getKey(), e.getValue());
    }
    assertThat(RELEASED.keySet())
        .as("новая SQL-миграция — добавьте её в RELEASED")
        .containsAll(actual.keySet());
  }

  @Test
  void historyFromBroken070IsAlignedWithReleasedV22() throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
        var st = c.createStatement()) {
      // Новая база: журнала ещё нет — ничего не делаем.
      MigrationHistoryFix.fix(c);
      st.execute(
          "CREATE TABLE flyway_schema_history (version TEXT, description TEXT, script TEXT,"
              + " checksum INTEGER)");
      st.execute(
          "INSERT INTO flyway_schema_history VALUES"
              + " ('21', 'lessons', 'V21__lessons.sql', 377036889),"
              + " ('22', 'tips and looks', 'V22__tips_and_looks.sql', -1966653243)");
      MigrationHistoryFix.fix(c);
      try (var rs =
          st.executeQuery(
              "SELECT description, script, checksum FROM flyway_schema_history WHERE version ="
                  + " '22'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("tips");
        assertThat(rs.getString(2)).isEqualTo("V22__tips.sql");
        assertThat(rs.getInt(3)).isEqualTo(MigrationHistoryFix.V22_CHECKSUM);
      }
      try (var rs =
          st.executeQuery("SELECT checksum FROM flyway_schema_history WHERE version = '21'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt(1)).isEqualTo(377036889);
      }
    }
  }
}
