package com.zello.zello.integration.github;

import com.zello.zello.auth.ZelloPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class GitHubConnectionController {
    private final GitHubConnectionFlow flow;

    public GitHubConnectionController(GitHubConnectionFlow flow) { this.flow = flow; }

    @PostMapping("/api/projects/{projectId}/github/connection")
    ResponseEntity<ConnectionStart> start(@PathVariable long projectId, @AuthenticationPrincipal ZelloPrincipal principal,
                                          Authentication authentication, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ConnectionStart(flow.start(projectId, principal, authentication, request)));
    }

    @GetMapping("/api/github/installations/callback")
    ResponseEntity<Void> callback(@RequestParam(required = false) String state,
                                  @RequestParam(name = "installation_id", required = false) String installationId,
                                  @AuthenticationPrincipal ZelloPrincipal principal, Authentication authentication,
                                  HttpServletRequest request) {
        String location = flow.finish(state, installationId, principal, authentication, request);
        return ResponseEntity.status(HttpStatus.SEE_OTHER).cacheControl(CacheControl.noStore()).location(URI.create(location)).build();
    }

    public record ConnectionStart(String authorizationUrl) { }
}
