package com.zello.zello.pullrequest;

import com.zello.zello.api.ApiException;
import com.zello.zello.domain.common.EntitySupport;
import com.zello.zello.domain.common.PublishStatus;
import com.zello.zello.domain.project.ProjectRepository;
import com.zello.zello.domain.pullrequest.PullRequest;
import com.zello.zello.domain.pullrequest.PullRequestRepository;
import com.zello.zello.domain.repository.GitRepository;
import com.zello.zello.domain.repository.GitRepositoryRepository;
import com.zello.zello.domain.user.UserRepository;
import com.zello.zello.integration.github.GitHubPullRequests;
import com.zello.zello.project.ProjectService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로컬 기록(PENDING) → GitHub 발행 → 결과 반영의 각 단계를 짧은 트랜잭션으로 나눈다.
 * GitHub 호출은 이 서비스 밖에서 하므로 외부 요청 동안 DB 트랜잭션이나 행 잠금을 잡지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class PullRequestPublishService {
    /** 발행 중 상태가 이 시간을 넘으면 중단된 요청으로 보고 다시 시도할 수 있게 한다. */
    private static final long STALE_SECONDS = 120;
    private final ProjectService projects;
    private final ProjectRepository projectRepository;
    private final GitRepositoryRepository repositories;
    private final PullRequestRepository pullRequests;
    private final UserRepository users;

    public PullRequestPublishService(ProjectService projects, ProjectRepository projectRepository, GitRepositoryRepository repositories,
                                     PullRequestRepository pullRequests, UserRepository users) {
        this.projects = projects;
        this.projectRepository = projectRepository;
        this.repositories = repositories;
        this.pullRequests = pullRequests;
        this.users = users;
    }

    public GitRepository authorize(long projectId, long userId, long repositoryId) {
        projects.requireWriter(projectId, userId);
        return connected(projectId, repositoryId);
    }

    @Transactional
    public Reservation reserve(long projectId, long userId, long repositoryId, Draft draft) {
        projectRepository.findLockedById(projectId).orElseThrow(ProjectService::notFound);
        projects.requireWriter(projectId, userId);
        var repository = connected(projectId, repositoryId);
        var now = EntitySupport.now();
        var rows = pullRequests.findByRepositoryId(repositoryId);
        var replay = pullRequests.findByPublishRequestKey(draft.requestKey());
        if (replay.isPresent()) {
            var row = replay.get();
            // 같은 키로 다른 내용을 보내면 이전 행의 내용으로 잘못 발행될 수 있으므로 거부한다.
            boolean sameRequest = row.getRepositoryId().equals(repositoryId) && Long.valueOf(userId).equals(row.getCreatedByUserId())
                    && row.getTitle().equals(draft.title()) && draft.body().equals(row.getBody()) && row.getHeadBranch().equals(draft.headBranch())
                    && row.getBaseBranch().equals(draft.baseBranch()) && row.getDraft() == draft.draft();
            if (!sameRequest) {
                throw new ApiException(409, "REQUEST_KEY_CONFLICT", "이미 다른 요청에 사용한 요청 키입니다.");
            }
            if (row.getPublishStatus() == PublishStatus.PUBLISHED) return new Reservation(row.getId(), false, true);
            if (row.getPublishStatus() == PublishStatus.PENDING && !stale(row, now)) throw inProgress();
            row.claim(now);
            return new Reservation(row.getId(), true, false);
        }
        for (var row : rows) {
            if (!row.getHeadBranch().equals(draft.headBranch()) || !row.getBaseBranch().equals(draft.baseBranch())) continue;
            boolean open = row.getPublishStatus() == PublishStatus.PUBLISHED && row.getState() == PullRequest.State.OPEN;
            if (open) throw new ApiException(409, "PULL_REQUEST_ALREADY_EXISTS", "같은 브랜치로 열린 PR이 이미 있어요.");
            if (row.getPublishStatus() == PublishStatus.PENDING && !stale(row, now)) throw inProgress();
        }
        var user = users.findWithGithubAccountById(userId).orElseThrow(() -> new ApiException(401, "AUTHENTICATION_REQUIRED", "로그인이 필요합니다."));
        var saved = pullRequests.saveAndFlush(PullRequest.builder().repository(repository).authorAccount(user.getGithubAccount()).createdByUser(user)
                .title(draft.title()).body(draft.body()).teamSummary(draft.body()).headBranch(draft.headBranch()).baseBranch(draft.baseBranch())
                .state(PullRequest.State.OPEN).draft(draft.draft()).publishStatus(PublishStatus.PENDING)
                .publishRequestKey(draft.requestKey()).lastFetchedAt(now).build());
        return new Reservation(saved.getId(), false, false);
    }

    /** GitHub에 만들어진 PR을 기록한다. 수집이 먼저 같은 PR을 저장했다면 그 행에 요약만 합치고 중복 행을 지운다. */
    @Transactional
    public PullRequest publish(long projectId, long pullRequestId, GitHubPullRequests.Remote remote) {
        projectRepository.findLockedById(projectId).orElseThrow(ProjectService::notFound);
        var row = pullRequests.findById(pullRequestId).orElseThrow(() -> new ApiException(404, "PULL_REQUEST_NOT_FOUND", "PR을 찾을 수 없습니다."));
        var now = EntitySupport.now();
        var collected = pullRequests.findByRepositoryId(row.getRepositoryId()).stream()
                .filter(other -> other.getGithubPrId() != null && other.getGithubPrId() == remote.id() && !other.getId().equals(row.getId()))
                .findFirst();
        if (collected.isPresent()) {
            collected.get().updateTeamSummary(row.getTeamSummary());
            pullRequests.delete(row);
            pullRequests.flush();
            return pullRequests.findDetail(collected.get().getId(), projectId).orElseThrow();
        }
        row.markPublished(remote.id(), remote.number(), remote.htmlUrl(), remote.head().sha(), remote.draft(),
                utc(remote.createdAt()), utc(remote.updatedAt()), now);
        pullRequests.flush();
        return pullRequests.findDetail(row.getId(), projectId).orElseThrow();
    }

    @Transactional
    public void fail(long pullRequestId, String message) {
        pullRequests.findById(pullRequestId).ifPresent(row -> { if (row.getPublishStatus() != PublishStatus.PUBLISHED) row.markFailed(message); });
    }

    @Transactional
    public void unknown(long pullRequestId, String message) {
        pullRequests.findById(pullRequestId).ifPresent(row -> { if (row.getPublishStatus() != PublishStatus.PUBLISHED) row.markUnknown(message); });
    }

    private GitRepository connected(long projectId, long repositoryId) {
        return repositories.findByIdAndProjectId(repositoryId, projectId)
                .filter(repo -> repo.getConnectionStatus() != GitRepository.ConnectionStatus.DISCONNECTED)
                .orElseThrow(() -> new ApiException(404, "REPOSITORY_NOT_FOUND", "연결된 저장소를 찾을 수 없습니다."));
    }

    private boolean stale(PullRequest row, LocalDateTime now) {
        return row.getLastFetchedAt() == null || row.getLastFetchedAt().isBefore(now.minusSeconds(STALE_SECONDS));
    }

    private ApiException inProgress() { return new ApiException(409, "PULL_REQUEST_IN_PROGRESS", "같은 PR을 등록하고 있어요. 잠시 후 다시 확인해 주세요."); }

    private static LocalDateTime utc(Instant instant) { return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }

    public record Draft(String title, String body, String headBranch, String baseBranch, boolean draft, String requestKey) { }

    /** published면 같은 요청 키로 이미 끝난 요청이고, retry면 결과 불명·실패 상태에서의 재시도다. */
    public record Reservation(Long pullRequestId, boolean retry, boolean published) { }
}
