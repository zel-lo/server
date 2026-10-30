package com.zello.zello.integration.github;

import com.zello.zello.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/github")
public class GitHubController {
    private final GitHubGateway gateway;
    private final GitHubTokenProvider tokens;
    private final GitHubProperties properties;

    public GitHubController(GitHubGateway gateway, GitHubTokenProvider tokens, GitHubProperties properties) {
        this.gateway = gateway;
        this.tokens = tokens;
        this.properties = properties;
    }

    @GetMapping("/config")
    ResponseEntity<Configuration> configuration() {
        return ok(new Configuration(properties.appSlug().isEmpty() ? null
                : "https://github.com/apps/" + properties.appSlug() + "/installations/new"));
    }

    @GetMapping("/installations")
    ResponseEntity<Page<InstallationView>> installations(Authentication authentication, HttpServletRequest request,
                                                        @RequestParam(defaultValue = "1") int page) {
        var result = gateway.installations(tokens.token(authentication, request), page);
        return ok(new Page<>(result.items().stream().map(installation -> new InstallationView(Long.toString(installation.id()),
                installation.account().login(), installation.account().type())).toList(), result.hasNext(), result.page()));
    }

    @GetMapping("/installations/{installationId}/repositories")
    ResponseEntity<Page<RepositoryOption>> repositories(Authentication authentication, HttpServletRequest request,
                                                        @PathVariable long installationId,
                                                        @RequestParam(defaultValue = "1") int page) {
        if (installationId <= 0) throw new ApiException(400, "INVALID_ID", "설치 ID를 확인해 주세요.");
        String token = tokens.token(authentication, request);
        gateway.requireInstallation(token, installationId);
        var result = gateway.repositories(token, installationId, page);
        return ok(new Page<>(result.items().stream().map(repo -> new RepositoryOption(Long.toString(repo.id()),
                repo.owner().login() + "/" + repo.name(), repo.privateRepository(), repo.archived())).toList(), result.hasNext(), result.page()));
    }

    @GetMapping("/installations/{installationId}")
    ResponseEntity<InstallationView> installation(Authentication authentication, HttpServletRequest request,
                                                 @PathVariable long installationId) {
        var item = gateway.requireInstallation(tokens.token(authentication, request), installationId);
        return ok(new InstallationView(Long.toString(item.id()), item.account().login(), item.account().type()));
    }

    private <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }

    public record Configuration(String installUrl) { }
    public record Page<T>(List<T> items, boolean hasNext, int page) { }
    public record InstallationView(String id, String owner, String ownerType) { }
    public record RepositoryOption(String id, String fullName, boolean privateRepository, boolean archived) { }
}
