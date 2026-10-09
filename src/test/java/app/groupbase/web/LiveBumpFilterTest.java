package app.groupbase.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Какие запросы поднимают версию данных: изменения – да, вход, опросы и чтение – нет. */
class LiveBumpFilterTest {

  @Test
  void changesCountButLoginPollsAndReadsDoNot() {
    assertThat(LiveBumpFilter.counts("POST", "/api/news")).isTrue();
    assertThat(LiveBumpFilter.counts("PATCH", "/api/groups/1")).isTrue();
    assertThat(LiveBumpFilter.counts("PUT", "/api/groups/1/members/2/role")).isTrue();
    assertThat(LiveBumpFilter.counts("POST", "/api/auth/register")).isTrue();
    assertThat(LiveBumpFilter.counts("PUT", "/api/me/avatar")).isTrue();

    assertThat(LiveBumpFilter.counts("GET", "/api/news")).isFalse();
    assertThat(LiveBumpFilter.counts("POST", "/api/auth/login")).isFalse();
    assertThat(LiveBumpFilter.counts("POST", "/api/auth/link/status")).isFalse();
    assertThat(LiveBumpFilter.counts("PATCH", "/api/me/preferences")).isFalse();
    assertThat(LiveBumpFilter.counts("POST", "/api/files/missing")).isFalse();
    assertThat(LiveBumpFilter.counts("POST", "/api/files")).isFalse();
    assertThat(LiveBumpFilter.counts("PUT", "/api/materials/5/dismissed")).isFalse();
    assertThat(LiveBumpFilter.counts("POST", "/api/host/peer/pair")).isFalse();
    assertThat(LiveBumpFilter.counts("POST", "/api/groups/1/schedule/preview")).isFalse();
    assertThat(LiveBumpFilter.counts("POST", "/login")).isFalse();
  }
}
