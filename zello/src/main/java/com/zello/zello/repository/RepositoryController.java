package com.zello.zello.repository;

import com.zello.zello.api.ApiException;
import com.zello.zello.auth.ZelloPrincipal;
import com.zello.zello.auth.AuthProperties;
import com.zello.zello.integration.github.GitHubOAuthRepositories;
import com.zello.zello.integration.github.GitHubGateway;
import com.zello.zello.integration.github.GitHubTokenProvider;
import com.zello.zello.project.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/repositories")
public class RepositoryController {
    private final RepositoryService service;
    private final ProjectService projects;
    private final GitHubGateway gateway;
    private final GitHubTokenProvider tokens;
    private final GitHubOAuthRepositories oauth;
    private final AuthProperties properties;

    public RepositoryController(RepositoryService service, ProjectService projects, GitHubGateway gateway, GitHubTokenProvider tokens,
                                GitHubOAuthRepositories oauth, AuthProperties properties) {
        this.service = service;
        this.projects = projects;
        this.gateway = gateway;
        this.tokens = tokens;
        this.oauth = oauth;
        this.properties = properties;
    }

    @GetMapping
    ResponseEntity<List<RepositoryService.RepositoryView>> list(@PathVariable long projectId,
            @AuthenticationPrincipal ZelloPrincipal principal, Authentication authentication, HttpServletRequest request) {
        var candidates = service.list(projectId, principal.userId());
        if (candidates.isEmpty()) return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(List.of());
        if (properties.github().appType() == AuthProperties.AppType.OAUTH) {
            String token = tokens.oauthToken(authentication, request);
            var visible = new java.util.ArrayList<RepositoryService.RepositoryView>();
            for (var repo : candidates) {
                try {
                    oauth.repository(token, Long.parseLong(repo.githubRepositoryId()));
                    visible.add(repo);
                } catch (ApiException exception) {
                    if (exception.status() != 404) throw exception;
                }
            }
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(visible);
        }
        String token = tokens.token(authentication, request);
        Map<String, Set<String>> accessible = new HashMap<>();
        for (var repo : candidates) {
            if (repo.installationId() == null) continue;
            if (accessible.containsKey(repo.installationId())) continue;
            try {
                accessible.put(repo.installationId(), gateway.accessibleRepositoryIds(token, Long.parseLong(repo.installationId())));
            } catch (ApiException exception) {
                if (exception.status() != 404) throw exception;
                accessible.put(repo.installationId(), Set.of());
            }
        }
        var visible = candidates.stream().filter(repo -> repo.installationId() != null && accessible.get(repo.installationId()).contains(repo.githubRepositoryId())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(visible);
    }

    @PostMapping
    ResponseEntity<RepositoryService.RepositoryView> connect(@PathVariable long projectId,
            @AuthenticationPrincipal ZelloPrincipal principal, Authentication authentication, HttpServletRequest request,
            @Valid @RequestBody ConnectRepository input) {
        // Authorize locally before any external request. Network calls do not hold a DB transaction.
        projects.requireMember(projectId, principal.userId(), true);
        RepositoryService.RepositoryView connected;
        if (properties.github().appType() == AuthProperties.AppType.OAUTH) {
            var source = oauth.repository(tokens.oauthToken(authentication, request), input.githubRepositoryId());
            connected = service.connectOAuth(projectId, principal.userId(), source);
        } else {
            if (input.githubInstallationId() == null) throw new ApiException(400, "INSTALLATION_REQUIRED", "기존 GitHub App 연결에는 설치 ID가 필요합니다.");
            var verified = gateway.verify(tokens.token(authentication, request), input.githubInstallationId(), input.githubRepositoryId());
            connected = service.connect(projectId, principal.userId(), verified);
        }
        return ResponseEntity.created(URI.create("/api/projects/" + projectId + "/repositories/" + connected.id()))
                .cacheControl(CacheControl.noStore()).body(connected);
    }

    @DeleteMapping("/{repositoryId}")
    ResponseEntity<Void> disconnect(@PathVariable long projectId, @PathVariable long repositoryId,
                                    @AuthenticationPrincipal ZelloPrincipal principal) {
        service.disconnect(projectId, repositoryId, principal.userId());
        return ResponseEntity.noContent().build();
    }

    public record ConnectRepository(@Positive Long githubInstallationId, @NotNull @Positive Long githubRepositoryId) { }
}
