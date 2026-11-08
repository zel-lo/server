package com.zello.zello.workspace;

import com.zello.zello.auth.ZelloPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/dashboard")
public class DashboardController {
    private final DashboardService service;
    public DashboardController(DashboardService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<DashboardService.Dashboard> dashboard(@PathVariable long projectId,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate week,
            @AuthenticationPrincipal ZelloPrincipal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.read(projectId, principal.userId(), week));
    }
}
