package com.zello.zello.pullrequest;

import com.zello.zello.api.ApiException;
import com.zello.zello.auth.ZelloPrincipal;
import com.zello.zello.domain.pullrequest.PullRequest;
import com.zello.zello.integration.github.GitHubOAuthRepositories;
import com.zello.zello.integration.github.GitHubPullRequests;
import com.zello.zello.integration.github.GitHubTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/pull-requests")
public class PullRequestController {
    private final PullRequestService service;
    private final PullRequestPublishService publisher;
    private final GitHubOAuthRepositories oauth;
    private final GitHubPullRequests pulls;
    private final GitHubTokenProvider tokens;

    public PullRequestController(PullRequestService service, PullRequestPublishService publisher, GitHubOAuthRepositories oauth,
                                 GitHubPullRequests pulls, GitHubTokenProvider tokens) {
        this.service = service;
        this.publisher = publisher;
        this.oauth = oauth;
        this.pulls = pulls;
        this.tokens = tokens;
    }

    @GetMapping
    ResponseEntity<PullRequestService.PullRequestPage> list(@PathVariable long projectId,
            @RequestParam(defaultValue = "all") String state, @RequestParam(required = false) String author,
            @RequestParam(required = false) String reviewer, @RequestParam(required = false) String review,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "30") int size,
            @AuthenticationPrincipal ZelloPrincipal principal, Authentication authentication, HttpServletRequest request) {
        var filter = filter(state, author, reviewer, review);
        if (page < 1 || page > 1000 || size < 1 || size > 100) throw new ApiException(400, "INVALID_PAGE", "페이지 범위를 확인해 주세요.");
        var candidates = service.connectedRepositories(projectId, principal.userId());
        var visible = new ArrayList<Long>();
        if (!candidates.isEmpty()) {
            // DB에 저장된 PR이라도 현재 사용자가 저장소에 접근할 수 있을 때만 보여준다.
            String token = tokens.oauthToken(authentication, request);
            for (var repo : candidates) {
                try {
                    oauth.repository(token, repo.getGithubRepoId());
                    visible.add(repo.getId());
                } catch (ApiException exception) {
                    if (exception.status() != 404) throw exception;
                }
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(projectId, principal.userId(), visible, filter, page, size));
    }

    @GetMapping("/{pullRequestId}")
    ResponseEntity<PullRequestService.PullRequestView> detail(@PathVariable long projectId, @PathVariable long pullRequestId,
            @AuthenticationPrincipal ZelloPrincipal principal, Authentication authentication, HttpServletRequest request) {
        var pull = service.find(projectId, principal.userId(), pullRequestId);
        // 목록과 같은 기준으로, 현재 사용자가 저장소에 접근할 수 있을 때만 본문을 보여준다.
        String token = tokens.oauthToken(authentication, request);
        var current = oauth.repository(token, pull.getRepository().getGithubRepoId());
        if (service.needsStats(pull)) {
            try {
                service.recordStats(pull.getId(), pulls.stats(token, current.owner().login(), current.name(), pull.getGithubNumber()));
                pull = service.find(projectId, principal.userId(), pullRequestId);
            } catch (ApiException exception) {
                // 변경 규모를 못 받아도 상세는 보여주고, 저장해 둔 값이 있으면 그 값을 쓴다.
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(pull));
    }

    /** 먼저 로컬에 기록하고 GitHub에 만든 뒤 결과를 반영한다. 같은 requestKey로 다시 보내면 중복 생성하지 않는다. */
    @PostMapping
    ResponseEntity<PullRequestService.PullRequestView> create(@PathVariable long projectId, @Valid @RequestBody CreatePullRequest input,
            @AuthenticationPrincipal ZelloPrincipal principal, Authentication authentication, HttpServletRequest request) {
        if (input.headBranch().equals(input.baseBranch())) throw new ApiException(400, "SAME_BRANCH", "작업 브랜치와 대상 브랜치는 달라야 합니다.");
        var repository = publisher.authorize(projectId, principal.userId(), input.repositoryId());
        String token = tokens.oauthToken(authentication, request);
        var current = oauth.repository(token, repository.getGithubRepoId());
        var body = input.body() == null || input.body().isBlank() ? null : input.body().strip();
        var draft = new PullRequestPublishService.Draft(input.title().strip(), input.teamSummary().strip(), body, input.headBranch(),
                input.baseBranch(), input.draft(), input.requestKey());
        var reservation = publisher.reserve(projectId, principal.userId(), repository.getId(), draft);
        if (reservation.published()) {
            var existing = service.find(projectId, principal.userId(), reservation.pullRequestId());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(existing));
        }
        var owner = current.owner().login();
        var remote = reservation.retry() ? findOpen(token, owner, current.name(), draft) : Optional.<GitHubPullRequests.Remote>empty();
        if (remote.isEmpty()) {
            try {
                remote = Optional.of(pulls.create(token, owner, current.name(), new GitHubPullRequests.NewPull(draft.title(),
                        Objects.requireNonNullElse(draft.body(), ""), draft.headBranch(), draft.baseBranch(), draft.draft())));
            } catch (ApiException exception) {
                if (!"GITHUB_RESULT_UNKNOWN".equals(exception.code())) {
                    publisher.fail(reservation.pullRequestId(), exception.getMessage());
                    throw exception;
                }
                // 응답이 유실됐을 수 있으니 실제로 만들어졌는지 확인하고, 확인되지 않으면 결과 불명으로 남긴다.
                remote = findOpen(token, owner, current.name(), draft);
                if (remote.isEmpty()) {
                    publisher.unknown(reservation.pullRequestId(), exception.getMessage());
                    throw exception;
                }
            }
        }
        var published = publisher.publish(projectId, reservation.pullRequestId(), remote.get());
        // 리뷰어 지정은 PR이 만들어진 뒤의 별도 요청이다. 실패해도 PR은 그대로 두고 경고만 돌려준다.
        String warning = null;
        var reviewers = input.reviewers() == null ? List.<String>of() : input.reviewers().stream().distinct().toList();
        if (!reviewers.isEmpty()) {
            try {
                var updated = pulls.requestReviewers(token, owner, current.name(), published.getGithubNumber(), reviewers);
                service.applyRequestedReviewers(published.getId(), updated.requestedReviewers());
            } catch (ApiException exception) {
                warning = "PR은 만들었지만 리뷰어를 지정하지 못했어요. " + exception.getMessage();
            }
        }
        return ResponseEntity.created(URI.create("/api/projects/" + projectId + "/pull-requests/" + published.getId()))
                .cacheControl(CacheControl.noStore()).body(service.detail(published).withWarning(warning));
    }

    /** zello 전용 팀 요약만 고친다. 제목·본문·리뷰어는 GitHub에서 수정한다. */
    @PatchMapping("/{pullRequestId}")
    ResponseEntity<PullRequestService.PullRequestView> update(@PathVariable long projectId, @PathVariable long pullRequestId,
            @Valid @RequestBody UpdatePullRequest input, @AuthenticationPrincipal ZelloPrincipal principal,
            Authentication authentication, HttpServletRequest request) {
        var pull = service.find(projectId, principal.userId(), pullRequestId);
        oauth.repository(tokens.oauthToken(authentication, request), pull.getRepository().getGithubRepoId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.updateTeamSummary(projectId, principal.userId(), pullRequestId, input.teamSummary(), input.version()));
    }

    private Optional<GitHubPullRequests.Remote> findOpen(String token, String owner, String name, PullRequestPublishService.Draft draft) {
        try {
            return pulls.findOpen(token, owner, name, draft.headBranch(), draft.baseBranch());
        } catch (ApiException exception) {
            return Optional.empty();
        }
    }

    @PostMapping("/sync")
    ResponseEntity<SyncResult> sync(@PathVariable long projectId, @AuthenticationPrincipal ZelloPrincipal principal,
                                    Authentication authentication, HttpServletRequest request) {
        var candidates = service.connectedRepositories(projectId, principal.userId());
        int collected = 0, skipped = 0;
        if (!candidates.isEmpty()) {
            String token = tokens.oauthToken(authentication, request);
            for (var repo : candidates) {
                try {
                    // 이름이 바뀐 저장소도 따라가도록 저장된 이름이 아니라 GitHub의 현재 이름을 쓴다.
                    var current = oauth.repository(token, repo.getGithubRepoId());
                    var fetched = pulls.fetch(token, current.owner().login(), current.name(), service.openGithubIds(repo.getId()));
                    collected += service.apply(projectId, principal.userId(), repo.getId(), fetched);
                } catch (ApiException exception) {
                    if (exception.status() != 404) throw exception;
                    skipped++;
                }
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SyncResult(collected, skipped, Instant.now()));
    }

    private PullRequestService.Filter filter(String state, String author, String reviewer, String review) {
        if (author != null && !"me".equals(author)) throw new ApiException(400, "INVALID_AUTHOR_FILTER", "작성자 필터를 확인해 주세요.");
        if (reviewer != null && !"me".equals(reviewer)) throw new ApiException(400, "INVALID_REVIEWER_FILTER", "검토자 필터를 확인해 주세요.");
        if (author != null && reviewer != null) throw new ApiException(400, "INVALID_FILTER", "작성자와 검토자 필터는 함께 쓸 수 없어요.");
        var states = switch (state) {
            case "open" -> EnumSet.of(PullRequest.State.OPEN);
            case "merged" -> EnumSet.of(PullRequest.State.MERGED);
            case "closed" -> EnumSet.of(PullRequest.State.CLOSED);
            case "all" -> EnumSet.allOf(PullRequest.State.class);
            default -> throw new ApiException(400, "INVALID_PULL_REQUEST_STATE", "PR 상태 필터를 확인해 주세요.");
        };
        // 코드 검토 화면의 검토 상태 필터. pending은 검토 대기, changes_requested는 수정 요청.
        var decision = review == null ? null : switch (review) {
            case "pending" -> PullRequest.ReviewDecision.PENDING;
            case "changes_requested" -> PullRequest.ReviewDecision.CHANGES_REQUESTED;
            default -> throw new ApiException(400, "INVALID_REVIEW_FILTER", "검토 상태 필터를 확인해 주세요.");
        };
        return new PullRequestService.Filter(states, "me".equals(author), "me".equals(reviewer), decision);
    }

    public record SyncResult(int collected, int skippedRepositories, Instant syncedAt) { }

    public record CreatePullRequest(@NotNull @Positive Long repositoryId, @NotBlank @Size(max = 255) String title,
                                    @NotBlank @Size(max = 255) @Pattern(regexp = "\\S+") String headBranch,
                                    @NotBlank @Size(max = 255) @Pattern(regexp = "\\S+") String baseBranch,
                                    @NotBlank @Size(max = 10000) String teamSummary, @Size(max = 60000) String body, boolean draft,
                                    @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{8,64}") String requestKey,
                                    @Size(max = 15) List<@NotBlank @Pattern(regexp = "[A-Za-z0-9-]{1,39}") String> reviewers) { }

    public record UpdatePullRequest(@Size(max = 10000) String teamSummary, @NotNull Long version) { }
}
