package com.zello.zello.pullrequest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.zello.zello.api.ApiException;
import com.zello.zello.domain.common.EntitySupport;
import com.zello.zello.domain.common.PublishStatus;
import com.zello.zello.domain.github.GitHubAccount;
import com.zello.zello.domain.github.GitHubAccountRepository;
import com.zello.zello.domain.project.ProjectRepository;
import com.zello.zello.domain.pullrequest.PullRequest;
import com.zello.zello.domain.pullrequest.PullRequestRepository;
import com.zello.zello.domain.pullrequest.PullRequestReviewer;
import com.zello.zello.domain.pullrequest.PullRequestReviewerRepository;
import com.zello.zello.domain.repository.GitRepository;
import com.zello.zello.domain.repository.GitRepositoryRepository;
import com.zello.zello.domain.user.UserRepository;
import com.zello.zello.integration.github.GitHubPullRequests;
import com.zello.zello.project.ProjectService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PullRequestService {
    private final ProjectService projects;
    private final ProjectRepository projectRepository;
    private final GitRepositoryRepository repositories;
    private final PullRequestRepository pullRequests;
    private final GitHubAccountRepository accounts;
    private final UserRepository users;
    private final PullRequestReviewerRepository reviewers;

    public PullRequestService(ProjectService projects, ProjectRepository projectRepository, GitRepositoryRepository repositories,
                              PullRequestRepository pullRequests, GitHubAccountRepository accounts, UserRepository users,
                              PullRequestReviewerRepository reviewers) {
        this.projects = projects;
        this.projectRepository = projectRepository;
        this.repositories = repositories;
        this.pullRequests = pullRequests;
        this.accounts = accounts;
        this.users = users;
        this.reviewers = reviewers;
    }

    public List<GitRepository> connectedRepositories(long projectId, long userId) {
        projects.requireMember(projectId, userId, false);
        return repositories.findByProjectIdOrderByIdAsc(projectId).stream()
                .filter(repo -> repo.getConnectionStatus() != GitRepository.ConnectionStatus.DISCONNECTED).toList();
    }

    public PullRequestPage list(long projectId, long userId, Collection<Long> repositoryIds, Filter filter, int page, int size) {
        projects.requireMember(projectId, userId, false);
        if (repositoryIds.isEmpty()) return new PullRequestPage(List.of(), page, false, new Counts(0, 0, 0));
        Long accountId = filter.mine() || filter.reviewing() ? users.findById(userId).orElseThrow(ProjectService::notFound).getGithubAccountId() : null;
        // GitHub 계정이 없으면 내 PR도, 내 검토 요청도 없다. 필터가 풀려 전체가 보이면 안 된다.
        if ((filter.mine() || filter.reviewing()) && accountId == null) return new PullRequestPage(List.of(), page, false, new Counts(0, 0, 0));
        Long authorAccountId = filter.mine() ? accountId : null;
        Long reviewerAccountId = filter.reviewing() ? accountId : null;
        // 검토 요청은 열린 PR에만 있다. 닫힌 PR에 남은 요청 기록은 "맡긴 리뷰어" 표시용이다.
        var states = filter.reviewing() ? EnumSet.of(PullRequest.State.OPEN) : filter.states();
        if (filter.reviewing() && !filter.states().contains(PullRequest.State.OPEN)) {
            return new PullRequestPage(List.of(), page, false, new Counts(0, 0, 0));
        }
        var requested = PullRequestReviewer.RequestStatus.REQUESTED;
        var result = pullRequests.search(projectId, repositoryIds, states, authorAccountId, reviewerAccountId, requested,
                PageRequest.of(page - 1, size));
        var reviewersByPull = reviewersOf(result.getContent().stream().map(PullRequest::getId).toList());
        return new PullRequestPage(result.getContent().stream()
                .map(pull -> PullRequestView.from(pull, null, reviewersByPull.getOrDefault(pull.getId(), List.of()))).toList(),
                page, result.hasNext(), filter.reviewing() ? new Counts(result.getTotalElements(), 0, 0)
                        : counts(projectId, repositoryIds, authorAccountId));
    }

    public PullRequest find(long projectId, long userId, long pullRequestId) {
        projects.requireMember(projectId, userId, false);
        return pullRequests.findDetail(pullRequestId, projectId)
                .filter(pull -> pull.getRepository().getConnectionStatus() != GitRepository.ConnectionStatus.DISCONNECTED)
                .orElseThrow(() -> new ApiException(404, "PULL_REQUEST_NOT_FOUND", "PR을 찾을 수 없습니다."));
    }

    public PullRequestView detail(PullRequest pull) {
        return PullRequestView.from(pull, pull.getBody(), reviewersOf(List.of(pull.getId())).getOrDefault(pull.getId(), List.of()));
    }

    /** zello에서만 쓰는 팀 요약을 고친다. GitHub 본문은 건드리지 않는다. */
    @Transactional
    public PullRequestView updateTeamSummary(long projectId, long userId, long pullRequestId, String teamSummary, long version) {
        projects.requireWriter(projectId, userId);
        var pull = find(projectId, userId, pullRequestId);
        if (pull.getVersion() != version) {
            throw new ApiException(409, "PULL_REQUEST_CONFLICT", "다른 사람이 먼저 수정했어요. 새로고침한 뒤 다시 시도해 주세요.");
        }
        pull.updateTeamSummary(teamSummary == null || teamSummary.isBlank() ? null : teamSummary.strip());
        pullRequests.saveAndFlush(pull);
        return detail(pull);
    }

    /** zello에서 PR을 만든 직후 GitHub에 요청한 리뷰어를 기록한다. */
    @Transactional
    public void applyRequestedReviewers(long pullRequestId, List<GitHubPullRequests.User> requested) {
        var pull = pullRequests.findById(pullRequestId).orElseThrow(() -> new ApiException(404, "PULL_REQUEST_NOT_FOUND", "PR을 찾을 수 없습니다."));
        Map<Long, PullRequestReviewer> stored = new HashMap<>();
        reviewers.findByIdPullRequestIdIn(List.of(pullRequestId)).forEach(row -> stored.put(row.getId().getReviewerAccountId(), row));
        syncReviewers(pull, requested, List.of(), false, stored, new HashMap<>(), EntitySupport.now());
    }

    public long githubId(long userId) {
        return users.findWithGithubAccountById(userId).orElseThrow(ProjectService::notFound).getGithubAccount().getGithubId();
    }

    private Map<Long, List<Reviewer>> reviewersOf(Collection<Long> pullRequestIds) {
        if (pullRequestIds.isEmpty()) return Map.of();
        var visible = EnumSet.of(PullRequestReviewer.RequestStatus.REQUESTED, PullRequestReviewer.RequestStatus.COMPLETED);
        return reviewers.findByIdPullRequestIdInAndRequestStatusIn(pullRequestIds, visible).stream()
                .sorted(java.util.Comparator.comparing(row -> row.getReviewerAccount().getLogin(), String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.groupingBy(row -> row.getId().getPullRequestId(),
                        Collectors.mapping(row -> new Reviewer(row.getReviewerAccount().getLogin(), row.getReviewerAccount().getAvatarUrl()),
                                Collectors.toList())));
    }

    /** 외부 호출은 트랜잭션 밖에서 끝내고, 여기서는 프로젝트 행 잠금 안에서 반영만 한다. */
    @Transactional
    public int apply(long projectId, long userId, long repositoryId, List<GitHubPullRequests.Fetched> fetched) {
        projectRepository.findLockedById(projectId).orElseThrow(ProjectService::notFound);
        projects.requireMember(projectId, userId, false);
        var repository = repositories.findByIdAndProjectId(repositoryId, projectId)
                .filter(repo -> repo.getConnectionStatus() != GitRepository.ConnectionStatus.DISCONNECTED)
                .orElseThrow(() -> new ApiException(404, "REPOSITORY_NOT_FOUND", "연결된 저장소를 찾을 수 없습니다."));
        var rows = pullRequests.findByRepositoryId(repositoryId);
        Map<Long, PullRequest> existing = rows.stream()
                .filter(pull -> pull.getGithubPrId() != null).collect(Collectors.toMap(PullRequest::getGithubPrId, Function.identity()));
        // zello에서 만들던 중이라 아직 GitHub 번호가 없는 행. 같은 브랜치의 PR이 수집되면 새 행을 만들지 않고 이어받는다.
        var inFlight = new ArrayList<>(rows.stream().filter(pull -> pull.getGithubPrId() == null
                && (pull.getPublishStatus() == PublishStatus.PENDING || pull.getPublishStatus() == PublishStatus.UNKNOWN)).toList());
        Map<Long, GitHubAccount> authors = new HashMap<>();
        Map<Long, Map<Long, PullRequestReviewer>> storedReviewers = new HashMap<>();
        if (!rows.isEmpty()) {
            for (var reviewer : reviewers.findByIdPullRequestIdIn(rows.stream().map(PullRequest::getId).toList())) {
                storedReviewers.computeIfAbsent(reviewer.getId().getPullRequestId(), id -> new HashMap<>())
                        .put(reviewer.getId().getReviewerAccountId(), reviewer);
            }
        }
        var now = EntitySupport.now();
        for (var item : fetched) {
            var remote = item.pull();
            var current = existing.get(remote.id());
            if (current == null) {
                var adopted = inFlight.stream().filter(pull -> pull.getHeadBranch().equals(remote.head().ref())
                        && pull.getBaseBranch().equals(remote.base().ref())).findFirst();
                if (adopted.isPresent()) {
                    current = adopted.get();
                    inFlight.remove(current);
                    current.markPublished(remote.id(), remote.number(), remote.htmlUrl(), remote.head().sha(), remote.draft(),
                            utc(remote.createdAt()), utc(remote.updatedAt()), now);
                }
            }
            // 응답 순서가 뒤바뀌어도 더 최신 데이터를 덮어쓰지 않는다.
            if (current != null && current.getGithubUpdatedAt() != null && utc(remote.updatedAt()).isBefore(current.getGithubUpdatedAt())) continue;
            var author = remote.user() == null ? null : authors.computeIfAbsent(remote.user().id(), id -> author(remote.user()));
            var state = remote.mergedAt() != null ? PullRequest.State.MERGED
                    : "open".equals(remote.state()) ? PullRequest.State.OPEN : PullRequest.State.CLOSED;
            var title = remote.title().length() > 255 ? remote.title().substring(0, 255) : remote.title();
            PullRequest target;
            if (current == null) {
                target = pullRequests.save(PullRequest.builder().repository(repository).authorAccount(author).githubPrId(remote.id())
                        .githubNumber(remote.number()).githubUrl(remote.htmlUrl()).title(title).body(remote.body())
                        .headBranch(remote.head().ref()).baseBranch(remote.base().ref()).headSha(remote.head().sha())
                        .mergeCommitSha(remote.mergeCommitSha()).state(state).draft(remote.draft() && state != PullRequest.State.MERGED)
                        .reviewDecision(item.reviewDecision()).openedAt(utc(remote.createdAt())).closedAt(utc(remote.closedAt()))
                        .mergedAt(utc(remote.mergedAt())).publishStatus(PublishStatus.PUBLISHED)
                        .githubUpdatedAt(utc(remote.updatedAt())).lastFetchedAt(now).build());
            } else {
                current.syncFromRemote(author, remote.htmlUrl(), title, remote.body(), remote.head().ref(), remote.base().ref(),
                        remote.head().sha(), remote.mergeCommitSha(), state, remote.draft(), item.reviewDecision(),
                        utc(remote.createdAt()), utc(remote.closedAt()), utc(remote.mergedAt()), utc(remote.updatedAt()), now);
                target = current;
            }
            // 리뷰 목록은 열린 PR만 조회하므로, 닫힌 PR은 기존 기록을 지우지 않는다.
            syncReviewers(target, remote.requestedReviewers(), item.reviewed(), target.getState() == PullRequest.State.OPEN,
                    storedReviewers.computeIfAbsent(target.getId(), id -> new HashMap<>()), authors, now);
        }
        repository.recordSync(now);
        return fetched.size();
    }

    /**
     * 맡긴 리뷰어를 GitHub 기준으로 맞춘다. 요청 중이면 REQUESTED, 리뷰를 제출했으면 COMPLETED.
     * complete가 true면 둘 다 아닌 사람(요청 취소)은 REMOVED로 뺀다.
     */
    private void syncReviewers(PullRequest pull, List<GitHubPullRequests.User> requested, List<GitHubPullRequests.User> reviewed,
                               boolean complete, Map<Long, PullRequestReviewer> stored, Map<Long, GitHubAccount> authors, LocalDateTime now) {
        var kept = new HashSet<Long>();
        for (var user : requested) {
            var account = authors.computeIfAbsent(user.id(), id -> author(user));
            kept.add(account.getId());
            var row = stored.get(account.getId());
            if (row == null) stored.put(account.getId(), saveReviewer(pull, account, PullRequestReviewer.RequestStatus.REQUESTED, now));
            else if (row.getRequestStatus() != PullRequestReviewer.RequestStatus.REQUESTED) row.requestAgain(now);
        }
        for (var user : reviewed) {
            var account = authors.computeIfAbsent(user.id(), id -> author(user));
            if (!kept.add(account.getId())) continue;
            var row = stored.get(account.getId());
            if (row == null) stored.put(account.getId(), saveReviewer(pull, account, PullRequestReviewer.RequestStatus.COMPLETED, now));
            else if (row.getRequestStatus() != PullRequestReviewer.RequestStatus.COMPLETED) row.completeReview();
        }
        if (!complete) return;
        stored.forEach((accountId, row) -> {
            if (row.getRequestStatus() != PullRequestReviewer.RequestStatus.REMOVED && !kept.contains(accountId)) row.remove();
        });
    }

    private PullRequestReviewer saveReviewer(PullRequest pull, GitHubAccount account, PullRequestReviewer.RequestStatus status, LocalDateTime now) {
        return reviewers.save(PullRequestReviewer.builder().pullRequest(pull).reviewerAccount(account)
                .requestStatus(status).requestedAt(now).updatedAt(now).build());
    }

    private GitHubAccount author(GitHubPullRequests.User user) {
        var type = "Bot".equals(user.type()) ? GitHubAccount.AccountType.BOT
                : "Organization".equals(user.type()) ? GitHubAccount.AccountType.ORGANIZATION : GitHubAccount.AccountType.USER;
        var account = accounts.findByGithubId(user.id()).orElseGet(() -> accounts.saveAndFlush(GitHubAccount.builder()
                .githubId(user.id()).login(user.login()).avatarUrl(user.avatarUrl()).accountType(type).build()));
        account.updateProfile(user.login(), account.getDisplayName(), user.avatarUrl());
        return account;
    }

    private Counts counts(long projectId, Collection<Long> repositoryIds, Long authorAccountId) {
        long open = 0, merged = 0, closed = 0;
        for (var row : pullRequests.countByState(projectId, repositoryIds, authorAccountId)) {
            long count = (Long) row[1];
            switch ((PullRequest.State) row[0]) {
                case OPEN -> open = count;
                case MERGED -> merged = count;
                case CLOSED -> closed = count;
            }
        }
        return new Counts(open, merged, closed);
    }

    private static LocalDateTime utc(Instant instant) { return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }

    public record Filter(EnumSet<PullRequest.State> states, boolean mine, boolean reviewing) { }

    public record Counts(long open, long merged, long closed) { }

    public record PullRequestPage(List<PullRequestView> items, int page, boolean hasNext, Counts counts) { }

    public record Author(String login, String avatarUrl) { }

    public record Reviewer(String login, String avatarUrl) { }

    /**
     * body(GitHub 원본)는 상세 응답에만 담는다. 목록에서는 응답이 커지지 않도록 null로 두고 JSON에서 뺀다.
     * reviewers는 요청 중이거나 리뷰를 제출한 사람이다. warning은 PR 생성 후 리뷰어 지정만 실패했을 때 담는다.
     */
    public record PullRequestView(String id, String repositoryId, String repositoryOwner, String repositoryName, Integer number,
                                  String url, String title, String headBranch, String baseBranch, String state, boolean draft,
                                  String reviewDecision, String teamSummary, Author author, List<Reviewer> reviewers, long version,
                                  Instant openedAt, Instant mergedAt, Instant githubUpdatedAt,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) String body,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) String warning) {
        static PullRequestView from(PullRequest pull, String body, List<Reviewer> reviewers) {
            var repo = pull.getRepository();
            var author = pull.getAuthorAccount();
            return new PullRequestView(pull.getId().toString(), repo.getId().toString(), repo.getOwnerLogin(), repo.getName(),
                    pull.getGithubNumber(), pull.getGithubUrl(), pull.getTitle(), pull.getHeadBranch(), pull.getBaseBranch(),
                    pull.getState().name(), pull.getDraft(), pull.getReviewDecision().name(), pull.getTeamSummary(),
                    author == null ? null : new Author(author.getLogin(), author.getAvatarUrl()), reviewers, pull.getVersion(),
                    instant(pull.getOpenedAt()), instant(pull.getMergedAt()), instant(pull.getGithubUpdatedAt()), body, null);
        }

        public PullRequestView withWarning(String warning) {
            return new PullRequestView(id, repositoryId, repositoryOwner, repositoryName, number, url, title, headBranch, baseBranch, state,
                    draft, reviewDecision, teamSummary, author, reviewers, version, openedAt, mergedAt, githubUpdatedAt, body, warning);
        }

        private static Instant instant(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }
    }
}
