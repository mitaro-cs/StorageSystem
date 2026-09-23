package app.groupbase.web.api;

import app.groupbase.audit.AuditService;
import app.groupbase.auth.Actor;
import app.groupbase.auth.Permission;
import app.groupbase.backup.BackupService;
import app.groupbase.backup.CloudFolders;
import app.groupbase.backup.RestoreStager;
import app.groupbase.web.ApiException;
import app.groupbase.web.Require;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Резервные копии: список, «сделать сейчас», скачать, куда складывать, восстановить. */
@RestController
@RequestMapping("/api/admin/backups")
class BackupController {

  record Choice(String label, String path) {}

  record View(
      boolean enabled,
      int keep,
      String dir,
      Choice cloud,
      List<Choice> choices,
      BackupService.Status status,
      List<BackupService.Info> items) {}

  record SettingsBody(String cloud) {}

  private final BackupService backups;
  private final RestoreStager restore;
  private final AuditService audit;
  private final app.groupbase.config.GroupbaseProperties props;

  BackupController(
      BackupService backups,
      RestoreStager restore,
      AuditService audit,
      app.groupbase.config.GroupbaseProperties props) {
    this.backups = backups;
    this.restore = restore;
    this.audit = audit;
    this.props = props;
  }

  @Require(Permission.MANAGE_INSTANCE)
  @GetMapping
  View list() throws IOException {
    List<CloudFolders.Folder> found = CloudFolders.detect();
    Path cloud = backups.cloudRoot().orElse(null);
    Choice chosen =
        cloud == null
            ? null
            : new Choice(
                found.stream()
                    .filter(f -> f.path().equals(cloud))
                    .map(CloudFolders.Folder::label)
                    .findFirst()
                    .orElse(cloud.getFileName().toString()),
                cloud.toString());
    return new View(
        props.backup().enabled(),
        props.backup().keep(),
        backups.dir().toAbsolutePath().toString(),
        chosen,
        found.stream().map(f -> new Choice(f.label(), f.path().toString())).toList(),
        backups.status(),
        backups.list());
  }

  @Require(Permission.MANAGE_INSTANCE)
  @PostMapping
  BackupService.Info create(Actor actor) throws IOException {
    BackupService.Info info = backups.create();
    audit.log(actor, null, "backup.create", "instance", null);
    return info;
  }

  @Require(Permission.MANAGE_INSTANCE)
  @PutMapping("/settings")
  View settings(Actor actor, @RequestBody SettingsBody b) throws IOException {
    try {
      backups.chooseCloud(b.cloud() == null || b.cloud().isBlank() ? null : Path.of(b.cloud()));
    } catch (IllegalArgumentException e) {
      throw ApiException.invalid("cloud", e.getMessage());
    }
    audit.log(actor, null, "backup.settings", "instance", null);
    return list();
  }

  @Require(Permission.MANAGE_INSTANCE)
  @GetMapping("/{name}")
  void download(@PathVariable String name, HttpServletResponse res) throws IOException {
    Path file = file(name);
    res.setContentType("application/zip");
    res.setContentLengthLong(Files.size(file));
    res.setHeader(
        HttpHeaders.CONTENT_DISPOSITION,
        ContentDisposition.attachment().filename(name).build().toString());
    Files.copy(file, res.getOutputStream());
  }

  @Require(Permission.MANAGE_INSTANCE)
  @PostMapping("/{name}/restore")
  RestoreStager.Result restore(Actor actor, @PathVariable String name) throws IOException {
    file(name);
    audit.log(actor, null, "backup.restore", "instance", null);
    try {
      return restore.fromBackup(name);
    } catch (IOException e) {
      throw ApiException.badRequest(e.getMessage());
    }
  }

  /** Восстановление из архива с компьютера (тело запроса — zip). */
  @Require(Permission.MANAGE_INSTANCE)
  @PutMapping("/restore")
  RestoreStager.Result upload(Actor actor, HttpServletRequest req) throws IOException {
    audit.log(actor, null, "backup.restore", "instance", null);
    try {
      return restore.fromUpload(req.getInputStream());
    } catch (IOException e) {
      throw ApiException.badRequest(e.getMessage());
    }
  }

  private Path file(String name) {
    try {
      Path f = backups.file(name);
      if (!Files.isRegularFile(f)) {
        throw ApiException.notFound();
      }
      return f;
    } catch (IllegalArgumentException e) {
      throw ApiException.notFound();
    }
  }
}
