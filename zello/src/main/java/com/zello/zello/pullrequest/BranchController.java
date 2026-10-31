package com.zello.zello.pullrequest;

import com.zello.zello.api.ApiException;
import com.zello.zello.auth.ZelloPrincipal;
import com.zello.zello.domain.repository.GitRepository;
import com.zello.zello.integration.github.GitHubOAuthRepositories;
import com.zello.zello.integration.github.GitHubPullRequests;
import com.zello.zello.integration.github.GitHubTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** PR 작성 화면에서 고를 브랜치와 리뷰어 후보. 저장된 값이 아니라 GitHub의 현재 값을 보여준다. */
@RestController
@RequestMapping("/api/projects/{projectId}/repositories/{repositoryId}")
public class BranchController {
    private final PullRequestService service;
    private final GitHubOAuthRepositories oauth;
    private final GitHubPullRequests pulls;
    private final GitHubTokenProvider tokens;

    public BranchController(PullRequestService service, GitHubOAuthRepositories oauth, GitHubPullRequests pulls, GitHubTokenProvider tokens) {
        this.service = service;
        this.oauth = oauth;
        this.pulls = pulls;
        this.tokens = tokens;
    }

    @GetMapping("/branches")
    ResponseEntity<BranchList> list(@PathVariable long projectId, @PathVariable long repositoryId,
            @AuthenticationPrincipal ZelloPrincipal principal, Authentication authentication, HttpServletRequest request) {
        var repository = connected(projectId, repositoryId, principal);
        String token = tokens.oauthToken(authentication, request);
        var current = oauth.repository(token, repository.getGithubRepoId());
        var branches = pulls.branches(token, current.owner().login(), current.name());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new BranchList(current.defaultBranch(), branches.names(), branches.hasMore()));
    }

    /** PR 작성자는 자기 PR의 리뷰어가 될 수 없으므로 본인은 뺀다. */
    @GetMapping("/reviewer-candidates")
    ResponseEntity<ReviewerCandidates> reviewers(@PathVariable long projectId, @PathVariable long repositoryId,
            @AuthenticationPrincipal ZelloPrincipal principal, Authentication authentication, HttpServletRequest request) {
        var repository = connected(projectId, repositoryId, principal);
        String token = tokens.oauthToken(authentication, request);
        var current = oauth.repository(token, repository.getGithubRepoId());
        var candidates = pulls.reviewerCandidates(token, current.owner().login(), current.name());
        long me = service.githubId(principal.userId());
        var users = candidates.users().stream().filter(user -> user.id() != me && !"Bot".equals(user.type()))
                .map(user -> new PullRequestService.Reviewer(user.login(), user.avatarUrl())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ReviewerCandidates(users, candidates.hasMore()));
    }

    private GitRepository connected(long projectId, long repositoryId, ZelloPrincipal principal) {
        return service.connectedRepositories(projectId, principal.userId()).stream()
                .filter(item -> item.getId() == repositoryId).findFirst()
                .orElseThrow(() -> new ApiException(404, "REPOSITORY_NOT_FOUND", "연결된 저장소를 찾을 수 없습니다."));
    }

    public record BranchList(String defaultBranch, List<String> branches, boolean hasMore) { }

    public record ReviewerCandidates(List<PullRequestService.Reviewer> users, boolean hasMore) { }
}
