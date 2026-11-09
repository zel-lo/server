package com.zello.zello.workspace;
import com.zello.zello.auth.ZelloPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class WorkspaceController {
    private final WorkspaceService service;
    private final WorkChangeService changes;
    public WorkspaceController(WorkspaceService service,WorkChangeService changes) { this.service=service; this.changes=changes; }
    private <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    @GetMapping("/summary") Object summary(@PathVariable long projectId,@AuthenticationPrincipal ZelloPrincipal p) { return ok(service.summary(projectId,p.userId())); }
    @GetMapping("/activities") Object activities(@PathVariable long projectId,@AuthenticationPrincipal ZelloPrincipal p,@RequestParam(defaultValue="1") int page) { return ok(service.activities(projectId,p.userId(),page)); }
    @GetMapping("/notifications") Object notifications(@PathVariable long projectId,@AuthenticationPrincipal ZelloPrincipal p,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="false") boolean unread) { return ok(service.notifications(projectId,p.userId(),page,unread)); }
    public record Read(boolean read) { }
    @PatchMapping("/notifications/{id}") Object read(@PathVariable long projectId,@PathVariable long id,@AuthenticationPrincipal ZelloPrincipal p,@RequestBody Read input) { service.read(projectId,p.userId(),id,input.read()); return ResponseEntity.noContent().build(); }
    @PostMapping("/notifications/read-all") Object readAll(@PathVariable long projectId,@AuthenticationPrincipal ZelloPrincipal p) { service.readAll(projectId,p.userId()); return ResponseEntity.noContent().build(); }
    @GetMapping("/patch-notes") Object notes(@PathVariable long projectId,@AuthenticationPrincipal ZelloPrincipal p,@RequestParam(defaultValue="1") int page) { return ok(service.notes(projectId,p.userId(),page)); }
    @GetMapping("/patch-note-changes") Object changeOptions(@PathVariable long projectId,@AuthenticationPrincipal ZelloPrincipal p,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="") @Size(max=255) String search) { return ok(service.changeOptions(projectId,p.userId(),page,search)); }
    @GetMapping("/sync-runs") Object syncRuns(@PathVariable long projectId,@AuthenticationPrincipal ZelloPrincipal p,@RequestParam(defaultValue="1") int page) { return ok(service.syncRuns(projectId,p.userId(),page)); }
    @GetMapping("/patch-notes/{id}") Object noteDetail(@PathVariable long projectId,@PathVariable long id,@AuthenticationPrincipal ZelloPrincipal p) { return ok(service.noteDetail(projectId,p.userId(),id)); }
    @PostMapping("/patch-notes/collect") Object collect(@PathVariable long projectId,@AuthenticationPrincipal ZelloPrincipal p) { changes.collect(projectId,p.userId()); return ResponseEntity.noContent().build(); }
    public record ChangeTitle(@NotBlank @Size(max=255) String title,@NotNull @PositiveOrZero Long version) { }
    @PatchMapping("/patch-notes/{id}/changes/{changeId}") Object changeTitle(@PathVariable long projectId,@PathVariable long id,@PathVariable long changeId,@AuthenticationPrincipal ZelloPrincipal p,@Valid @RequestBody ChangeTitle input) { return ok(service.writeChangeTitle(projectId,p.userId(),id,changeId,input.title(),input.version())); }
    public record NoteInput(@NotBlank @Size(max=255) String title,@NotNull @Size(max=20000) String summary,Long version,boolean publish,@Size(max=100) java.util.List<@NotNull @Positive Long> changeIds) { }
    @PostMapping("/patch-notes") Object create(@PathVariable long projectId,@AuthenticationPrincipal ZelloPrincipal p,@Valid @RequestBody NoteInput input) { return ok(service.saveNote(projectId,p.userId(),null,input.title(),input.summary(),null,input.publish(),input.changeIds())); }
    @PatchMapping("/patch-notes/{id}") Object edit(@PathVariable long projectId,@PathVariable long id,@AuthenticationPrincipal ZelloPrincipal p,@Valid @RequestBody NoteInput input) { return ok(service.saveNote(projectId,p.userId(),id,input.title(),input.summary(),input.version(),input.publish(),input.changeIds())); }
}
