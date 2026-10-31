package com.zello.zello.pullrequest;

import com.zello.zello.api.ApiException;
import com.zello.zello.auth.ZelloPrincipal;
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

/** PR 작성 화면에서 고를 브랜치 목록. 저장된 값이 아니라 GitHub의 현재 브랜치와 기본 브랜치를 보여준다. */
@RestController
@RequestMapping("/api/projects/{projectId}/repositories/{repositoryId}/branches")
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

    @GetMapping
    ResponseEntity<BranchList> list(@PathVariable long projectId, @PathVariable long repositoryId,
            @AuthenticationPrincipal ZelloPrincipal principal, Authentication authentication, HttpServletRequest request) {
        var repository = service.connectedRepositories(projectId, principal.userId()).stream()
                .filter(item -> item.getId() == repositoryId).findFirst()
                .orElseThrow(() -> new ApiException(404, "REPOSITORY_NOT_FOUND", "연결된 저장소를 찾을 수 없습니다."));
        String token = tokens.oauthToken(authentication, request);
        var current = oauth.repository(token, repository.getGithubRepoId());
        var branches = pulls.branches(token, current.owner().login(), current.name());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new BranchList(current.defaultBranch(), branches.names(), branches.hasMore()));
    }

    public record BranchList(String defaultBranch, List<String> branches, boolean hasMore) { }
}
