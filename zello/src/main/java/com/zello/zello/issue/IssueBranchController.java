package com.zello.zello.issue;

import com.zello.zello.auth.ZelloPrincipal;
import com.zello.zello.integration.github.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/issues/{issueId}/branch")
public class IssueBranchController {
    private final IssueService issues;
    private final IssueBranchService service;
    private final GitHubBranches branches;
    private final GitHubOAuthRepositories repositories;
    private final GitHubTokenProvider tokens;
    public IssueBranchController(IssueService issues, IssueBranchService service, GitHubBranches branches,
                                 GitHubOAuthRepositories repositories, GitHubTokenProvider tokens) {
        this.issues = issues; this.service = service; this.branches = branches; this.repositories = repositories; this.tokens = tokens;
    }
    @GetMapping
    ResponseEntity<WorkBranch> plan(@PathVariable long projectId, @PathVariable long issueId,
            @AuthenticationPrincipal ZelloPrincipal user, Authentication authentication, HttpServletRequest request) {
        var issue = issues.find(projectId, user.userId(), issueId, false);
        var current = repositories.repository(tokens.oauthToken(authentication, request), issue.getRepository().getGithubRepoId());
        return ok(view(current, service.name(issue), false, issues.view(issue)));
    }
    @PostMapping
    ResponseEntity<WorkBranch> start(@PathVariable long projectId, @PathVariable long issueId, @Valid @RequestBody Start input,
            @AuthenticationPrincipal ZelloPrincipal user, Authentication authentication, HttpServletRequest request) {
        var issue = issues.find(projectId, user.userId(), issueId, true);
        service.requireStart(issue, input.version());
        String token = tokens.oauthToken(authentication, request);
        var current = repositories.repository(token, issue.getRepository().getGithubRepoId());
        var name = service.name(issue);
        // No DB transaction or lock is held during the GitHub call.
        var result = branches.ensure(token, current.owner().login(), current.name(), current.defaultBranch(), name);
        var updated = service.started(projectId, user.userId(), issueId, name, current.defaultBranch());
        return ok(view(current, name, result.created(), updated));
    }
    private WorkBranch view(GitHubGateway.RemoteRepository current, String name, boolean created, IssueService.View updated) {
        String repository = current.owner().login() + "/" + current.name();
        String base = updated.workBranchBase() == null ? current.defaultBranch() : updated.workBranchBase();
        return new WorkBranch(repository, name, base, "https://github.com/" + repository + "/tree/" + name,
                "git fetch origin\ngit switch " + name, updated.workBranch() != null, created, updated);
    }
    private <T> ResponseEntity<T> ok(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
    public record Start(@NotNull @PositiveOrZero Long version) { }
    public record WorkBranch(String repository, String name, String baseBranch, String url, String commands,
                             boolean ready, boolean created, IssueService.View issue) { }
}
