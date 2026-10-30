package com.zello.zello.integration.github;

import com.zello.zello.auth.ZelloPrincipal;
import com.zello.zello.domain.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/github")
public class GitHubOAuthController {
    private final GitHubOAuthRepositories github;
    private final GitHubTokenProvider tokens;
    private final UserRepository users;

    public GitHubOAuthController(GitHubOAuthRepositories github, GitHubTokenProvider tokens, UserRepository users) {
        this.github = github; this.tokens = tokens; this.users = users;
    }

    @GetMapping("/accounts")
    ResponseEntity<GitHubGateway.Page<Account>> accounts(Authentication authentication, HttpServletRequest request,
            @AuthenticationPrincipal ZelloPrincipal principal, @RequestParam(defaultValue = "1") int page) {
        String token = tokens.oauthToken(authentication, request);
        var organizations = github.organizations(token, page);
        var choices = new ArrayList<Account>();
        {
            var account = users.findWithGithubAccountById(principal.userId()).orElseThrow().getGithubAccount();
            choices.add(new Account(account.getGithubId().toString(), account.getLogin(), "USER"));
        }
        organizations.items().forEach(org -> choices.add(new Account(Long.toString(org.id()), org.login(), "ORGANIZATION")));
        return ok(new GitHubGateway.Page<>(choices, organizations.hasNext(), organizations.page()));
    }

    @GetMapping("/repositories")
    ResponseEntity<GitHubGateway.Page<RepositoryOption>> repositories(Authentication authentication, HttpServletRequest request,
            @RequestParam(defaultValue = "all") String scope, @RequestParam(required = false) String organization,
            @RequestParam(defaultValue = "1") int page) {
        var result = github.repositories(tokens.oauthToken(authentication, request), scope, organization, page);
        var options = result.items().stream().map(repo -> new RepositoryOption(Long.toString(repo.id()),
                repo.owner().login() + "/" + repo.name(), repo.owner().login(), repo.owner().type(), repo.privateRepository(), repo.archived())).toList();
        return ok(new GitHubGateway.Page<>(options, result.hasNext(), result.page()));
    }

    private <T> ResponseEntity<T> ok(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
    public record Account(String id, String login, String type) { }
    public record RepositoryOption(String id, String fullName, String owner, String ownerType, boolean privateRepository, boolean archived) { }
}
