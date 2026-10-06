package app.groupbase.accounts;

import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.content.Markdown;
import app.groupbase.store.SettingsStore;
import app.groupbase.web.ApiException;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Правила сайта и конфиденциальность (0.8.1). Общие правила — в интерфейсе (одинаковые у всех
 * сайтов, версия {@link #BASE}); администратор может дописать правила своей группы. Версия правил —
 * «общая.дописанные»: поменялась любая часть — каждого спросят согласие снова.
 */
@Service
public class Terms {

  /** Версия общих правил в интерфейсе (web/src/lib/terms/TermsText.svelte). */
  public static final int BASE = 1;

  static final String EXTRA = "terms.extra";
  static final String EXTRA_VERSION = "terms.extra_version";
  static final String UPDATED = "terms.updated_at";
  static final int MAX = 20_000;

  public record View(String version, String extraMd, String extraHtml, Long updatedAt) {}

  private final SettingsStore settings;
  private final AuditService audit;
  private final Clock clock;

  public Terms(SettingsStore settings, AuditService audit, Clock clock) {
    this.settings = settings;
    this.audit = audit;
    this.clock = clock;
  }

  public String version() {
    return BASE + "." + settings.get(EXTRA_VERSION).orElse("0");
  }

  public View view() {
    String md = settings.get(EXTRA).orElse("");
    Long at = settings.get(UPDATED).map(Long::valueOf).orElse(null);
    return new View(version(), md, md.isBlank() ? "" : Markdown.render(md), at);
  }

  /** Дописанные правила группы; поменялись — новая версия, согласие спросят снова. */
  public View update(Actor actor, String markdown) {
    String md = markdown == null ? "" : markdown.strip();
    if (md.length() > MAX) {
      throw ApiException.invalid("extra", "Правила длиннее " + MAX + " знаков");
    }
    if (!md.equals(settings.get(EXTRA).orElse(""))) {
      int next = Integer.parseInt(settings.get(EXTRA_VERSION).orElse("0")) + 1;
      settings.set(EXTRA, md);
      settings.set(EXTRA_VERSION, String.valueOf(next));
      settings.set(UPDATED, String.valueOf(clock.millis()));
      audit.log(actor, null, "terms.update", "instance", null, Map.of("version", version()));
    }
    return view();
  }
}
