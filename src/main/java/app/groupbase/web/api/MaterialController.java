package app.groupbase.web.api;

import app.groupbase.auth.Actor;
import app.groupbase.content.CommentService;
import app.groupbase.content.MaterialService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MaterialController {

  record NameBody(String name) {}

  record CommentBody(String body) {}

  private final MaterialService materials;
  private final CommentService comments;

  MaterialController(MaterialService materials, CommentService comments) {
    this.materials = materials;
    this.comments = comments;
  }

  @GetMapping("/api/subjects/{subjectId}/materials")
  MaterialService.Listing list(
      Actor actor, @PathVariable long subjectId, @RequestParam(required = false) Long folder) {
    return materials.list(actor, subjectId, folder);
  }

  @PostMapping("/api/subjects/{subjectId}/materials")
  MaterialService.Item create(
      Actor actor, @PathVariable long subjectId, @RequestBody MaterialService.Input in) {
    return materials.create(actor, subjectId, in);
  }

  @PostMapping("/api/subjects/{subjectId}/folders")
  MaterialService.Folder folder(
      Actor actor, @PathVariable long subjectId, @RequestBody MaterialService.FolderInput in) {
    return materials.createFolder(actor, subjectId, in);
  }

  @PatchMapping("/api/folders/{id}")
  Map<String, String> renameFolder(Actor actor, @PathVariable long id, @RequestBody NameBody b) {
    materials.renameFolder(actor, id, b.name());
    return Map.of("status", "ok");
  }

  @DeleteMapping("/api/folders/{id}")
  Map<String, String> deleteFolder(Actor actor, @PathVariable long id) {
    materials.deleteFolder(actor, id);
    return Map.of("status", "ok");
  }

  @GetMapping("/api/materials/recent")
  List<MaterialService.Item> recent(
      Actor actor,
      @RequestParam(required = false) Long group,
      @RequestParam(defaultValue = "20") int limit) {
    return materials.recent(actor, group, Math.clamp(limit, 1, 100));
  }

  @GetMapping("/api/materials/pending")
  List<MaterialService.Item> pending(Actor actor) {
    return materials.pending(actor);
  }

  @GetMapping("/api/materials/{id}")
  MaterialService.Item get(Actor actor, @PathVariable long id) {
    return materials.get(actor, id);
  }

  @PatchMapping("/api/materials/{id}")
  MaterialService.Item update(
      Actor actor, @PathVariable long id, @RequestBody MaterialService.Input in) {
    return materials.update(actor, id, in);
  }

  @DeleteMapping("/api/materials/{id}")
  Map<String, String> delete(Actor actor, @PathVariable long id) {
    materials.delete(actor, id);
    return Map.of("status", "ok");
  }

  @PostMapping("/api/materials/{id}/{action:approve|reject|hide|unhide}")
  Map<String, String> moderate(Actor actor, @PathVariable long id, @PathVariable String action) {
    materials.moderate(actor, id, action);
    return Map.of("status", "ok");
  }

  @GetMapping("/api/materials/{id}/comments")
  List<CommentService.Comment> comments(Actor actor, @PathVariable long id) {
    return comments.list(actor, CommentService.Parent.MATERIAL, id);
  }

  @PostMapping("/api/materials/{id}/comments")
  CommentService.Comment comment(Actor actor, @PathVariable long id, @RequestBody CommentBody b) {
    return comments.add(actor, CommentService.Parent.MATERIAL, id, b.body());
  }
}
