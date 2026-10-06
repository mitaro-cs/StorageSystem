package app.groupbase.web.api;

import app.groupbase.accounts.Terms;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Permission;
import app.groupbase.web.Public;
import app.groupbase.web.Require;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Правила сайта: читать — всем (и до входа), дописывать правила группы — администратору. */
@RestController
class TermsController {

  record ExtraBody(String extra) {}

  private final Terms terms;

  TermsController(Terms terms) {
    this.terms = terms;
  }

  @Public
  @GetMapping("/api/terms")
  Terms.View view() {
    return terms.view();
  }

  @Require(Permission.MANAGE_INSTANCE)
  @PutMapping("/api/admin/terms")
  Terms.View update(Actor actor, @RequestBody ExtraBody b) {
    return terms.update(actor, b.extra());
  }
}
