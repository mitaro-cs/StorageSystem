package app.groupbase.notify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NotificationPrefsTest {

  @Test
  void defaultsAreHomeworkAndRemindersButNoDigest() {
    var d = NotificationPrefs.Prefs.DEFAULT;
    assertThat(d.homework()).isTrue();
    assertThat(d.reminders()).isTrue();
    assertThat(d.materials()).isFalse();
    assertThat(d.digest()).isFalse();
    assertThat(d.digestAt()).isEqualTo(8 * 60);
  }

  @Test
  void newsFilter() {
    var all = new NotificationPrefs.Prefs(true, "all", false, true, false, 480);
    var urgent = new NotificationPrefs.Prefs(true, "urgent", false, true, false, 480);
    var none = new NotificationPrefs.Prefs(true, "none", false, true, false, 480);
    assertThat(all.wantsNews(false)).isTrue();
    assertThat(urgent.wantsNews(false)).isFalse();
    assertThat(urgent.wantsNews(true)).isTrue();
    assertThat(none.wantsNews(true)).isFalse();
  }
}
