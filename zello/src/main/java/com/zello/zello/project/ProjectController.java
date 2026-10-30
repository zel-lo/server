package com.zello.zello.project;

import com.zello.zello.auth.ZelloPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    private final ProjectService service;

    public ProjectController(ProjectService service) { this.service = service; }

    @GetMapping
    ResponseEntity<List<ProjectService.ProjectView>> list(@AuthenticationPrincipal ZelloPrincipal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(principal.userId()));
    }

    @PostMapping
    ResponseEntity<ProjectService.ProjectView> create(@AuthenticationPrincipal ZelloPrincipal principal,
                                                     @Valid @RequestBody CreateProject input) {
        var project = service.create(principal.userId(), input.name(), input.description());
        return ResponseEntity.created(URI.create("/api/projects/" + project.id())).cacheControl(CacheControl.noStore()).body(project);
    }

    @GetMapping("/{projectId}")
    ResponseEntity<ProjectService.ProjectView> detail(@PathVariable long projectId, @AuthenticationPrincipal ZelloPrincipal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(projectId, principal.userId()));
    }

    public record CreateProject(@NotBlank @Size(max = 100) String name, @Size(max = 4000) String description) { }
}
